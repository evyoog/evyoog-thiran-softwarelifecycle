package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.importqueue.ImportBatch;
import com.vyoog.importqueue.ImportCandidate;
import com.vyoog.importqueue.ImportService;
import com.vyoog.importqueue.JdbcExtractionProgress;
import com.vyoog.importqueue.UploadKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0940 (F31): against a real PostgreSQL and a stub model provider, an AI extraction makes its model calls outside any database
 * transaction (the tests run with the guard in {@code fail} mode, so a call inside one would fail the call), keeps each finished
 * step, records why it failed, and is continued by extracting again. The other paths that used to call a model inside a
 * transaction (per-candidate checks and trace proposals, enrichment after a write, an attachment upload) are exercised here too.
 */
@AutoConfigureMockMvc
class ResumableExtractionIT extends IntegrationTestBase {

    private static final HttpServer STUB;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** How many triage (chunk) calls the stub has answered or refused, and the number of the call to refuse (0 = none). */
    private static final AtomicInteger TRIAGE_CALLS = new AtomicInteger();
    private static final AtomicInteger FAIL_TRIAGE_ON = new AtomicInteger();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.setExecutor(Executors.newCachedThreadPool());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        STUB.createContext("/v1/embeddings", ex -> {
            ex.getRequestBody().readAllBytes();
            StringBuilder vector = new StringBuilder("[");
            for (int i = 0; i < 1536; i++) vector.append(i == 0 ? "" : ",").append(i == 0 ? "0.5" : "0");
            reply(ex, 200, "{\"data\":[{\"embedding\":" + vector + "]}],\"usage\":{\"prompt_tokens\":5}}");
        });
        STUB.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String content;
            if (body.contains("Excerpt location:")) {
                int call = TRIAGE_CALLS.incrementAndGet();
                if (call == FAIL_TRIAGE_ON.get()) {
                    reply(ex, 400, "{\"error\":{\"message\":\"stub refuses this call\"}}"); // a definite answer: not retried
                    return;
                }
                List<String> findings = new ArrayList<>();
                Matcher m = Pattern.compile("RULE-(\\d+)").matcher(body);
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                while (m.find()) seen.add(m.group(1));
                for (String n : seen) {
                    findings.add("{\"category\":\"SYSTEM_BEHAVIOUR\",\"statement\":\"Rule " + n + " applies.\","
                        + "\"evidence\":\"The system shall handle RULE-" + n + " correctly\",\"importance\":\"HIGH\"}");
                }
                content = "{\"findings\":[" + String.join(",", findings) + "],\"discardedCount\":0,\"noiseSummary\":\"\"}";
            } else if (body.contains("DRAFT DESCRIPTION:")) {
                content = "{\"unsupportedClaims\":[],\"guidance\":\"\"}";
            } else if (body.contains("Findings to brief:")) {
                List<String> briefs = new ArrayList<>();
                Matcher m = Pattern.compile("\\[index (\\d+)\\]").matcher(body);
                while (m.find()) {
                    briefs.add("{\"index\":" + m.group(1) + ",\"title\":\"Handle rule\",\"statement\":\"The system shall handle the rule.\","
                        + "\"type\":\"FUNCTIONAL\",\"priority\":\"HIGH\",\"acceptanceCriteria\":[\"It is handled.\"],"
                        + "\"description\":\"What this asks for.\",\"entails\":[],\"dependsOn\":[],\"openQuestions\":[],\"readiness\":\"CLEAR\"}");
                }
                content = "{\"briefs\":[" + String.join(",", briefs) + "]}";
            } else if (body.contains("themes")) {
                content = "{\"description\":\"A specification of numbered rules.\",\"themes\":[\"rules\"]}";
            } else {
                content = "{\"links\":[],\"rewrittenStatement\":\"The system shall answer in 2 s.\",\"changes\":[]}";
            }
            reply(ex, 200, JSON.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 9, "completion_tokens", 6))));
        });
        STUB.start();
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        r.add("vyoog.ai.enabled", () -> "true");
        r.add("vyoog.ai.api-key", () -> "it-key");
        r.add("vyoog.ai.api-url", () -> base + "/v1/chat/completions");
        r.add("vyoog.ai.embeddings-url", () -> base + "/v1/embeddings");
    }

    @AfterAll
    static void stop() { STUB.stop(0); }

    @Autowired ImportService importService;
    @Autowired JdbcExtractionProgress extractionProgress;
    @Autowired com.vyoog.attachments.AttachmentService attachmentService;
    @Autowired MeterRegistry meters;
    @Autowired MockMvc mvc;

    @BeforeEach
    void stubWorks() {
        FAIL_TRIAGE_ON.set(0);
    }

    /** Eight paragraphs of about 3,000 characters, each stating one numbered rule: several chunks. */
    private static String document() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            if (i > 1) text.append("\n\n");
            text.append("The system shall handle RULE-").append(i).append(" correctly. ").append("Background detail. ".repeat(150));
        }
        return text.toString();
    }

    private ImportBatch aBatch(String rawText) {
        return importService.upload(unique("spec") + ".txt", null, UploadKind.FREEFORM, rawText, newUser("importer"));
    }

    private String stateOf(UUID batchId) {
        return jdbc.queryForObject("SELECT state FROM import_batch WHERE id = ?", String.class, batchId);
    }

    private int stepsOf(UUID batchId) {
        return jdbc.queryForObject("SELECT count(*) FROM import_extraction_step WHERE batch_id = ?", Integer.class, batchId);
    }

    private double callsInTransaction() {
        var counter = meters.find("network.calls.in-transaction").counter();
        return counter == null ? 0 : counter.count();
    }

    /** How many chunks this document is: the triage calls an uninterrupted extraction makes. */
    private int chunkCount() {
        int before = TRIAGE_CALLS.get();
        importService.extractCandidates(aBatch(document()).getId());
        return TRIAGE_CALLS.get() - before;
    }

    @Test
    void VYB0940_AC32_anExtractionMakesItsModelCallsOutsideATransactionAndLeavesNoStepsBehind() {
        ImportBatch batch = aBatch(document());

        List<ImportCandidate> saved = importService.extractCandidates(batch.getId());

        assertThat(saved).hasSize(8);
        assertThat(stateOf(batch.getId())).isEqualTo("EXTRACTED");
        assertThat(stepsOf(batch.getId())).as("the steps are forgotten once the candidates exist").isZero();
        assertThat(jdbc.queryForObject("SELECT extraction_error FROM import_batch WHERE id = ?", String.class, batch.getId())).isNull();
        assertThat(callsInTransaction()).isZero();
    }

    @Test
    void VYB0940_AC33_aFailurePartWayKeepsTheFinishedStepsSaysWhyAndMakesNoCandidate() {
        ImportBatch batch = aBatch(document());
        FAIL_TRIAGE_ON.set(TRIAGE_CALLS.get() + 3);

        assertThatThrownBy(() -> importService.extractCandidates(batch.getId())).isInstanceOf(AiProviderUnavailableException.class);

        assertThat(stateOf(batch.getId())).isEqualTo("EXTRACTION_FAILED");
        assertThat(jdbc.queryForObject("SELECT extraction_error FROM import_batch WHERE id = ?", String.class, batch.getId())).isNotBlank();
        assertThat(stepsOf(batch.getId())).as("the two chunks that were read").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_candidate WHERE batch_id = ?", Integer.class, batch.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_document_analysis WHERE batch_id = ?", Integer.class, batch.getId())).isZero();
    }

    @Test
    void VYB0940_AC34_extractingAgainContinuesFromTheLastSavedStepAndFinishesWithEveryCandidate() {
        int chunks = chunkCount();
        ImportBatch batch = aBatch(document());
        FAIL_TRIAGE_ON.set(TRIAGE_CALLS.get() + 3);
        assertThatThrownBy(() -> importService.extractCandidates(batch.getId())).isInstanceOf(AiProviderUnavailableException.class);
        FAIL_TRIAGE_ON.set(0);
        int before = TRIAGE_CALLS.get();

        List<ImportCandidate> saved = importService.extractCandidates(batch.getId());

        assertThat(TRIAGE_CALLS.get() - before).as("only the chunks not yet read are sent to the model").isEqualTo(chunks - 2);
        assertThat(saved).as("the same eight candidates an uninterrupted extraction makes").hasSize(8);
        assertThat(stateOf(batch.getId())).isEqualTo("EXTRACTED");
        assertThat(stepsOf(batch.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT extraction_error FROM import_batch WHERE id = ?", String.class, batch.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_document_analysis WHERE batch_id = ?", Integer.class, batch.getId())).isEqualTo(1);
        assertThat(callsInTransaction()).isZero();
    }

    @Test
    void VYB0940_AC35_stepsMadeFromDifferentTextAreNotReusedIfTheDocumentChangedBetweenAttempts() {
        int chunks = chunkCount();
        ImportBatch batch = aBatch(document());
        FAIL_TRIAGE_ON.set(TRIAGE_CALLS.get() + 3);
        assertThatThrownBy(() -> importService.extractCandidates(batch.getId())).isInstanceOf(AiProviderUnavailableException.class);
        FAIL_TRIAGE_ON.set(0);
        jdbc.update("UPDATE import_batch SET raw_text = raw_text || ? WHERE id = ?", "\n\nOne more paragraph of background detail.", batch.getId());
        int before = TRIAGE_CALLS.get();

        importService.extractCandidates(batch.getId());

        assertThat(TRIAGE_CALLS.get() - before).as("every chunk is read again").isGreaterThanOrEqualTo(chunks);
        assertThat(stateOf(batch.getId())).isEqualTo("EXTRACTED");
    }

    @Test
    void VYB0940_AC36_onlyOneExtractionOfABatchRunsAtATimeAndAnAbandonedClaimIsTakenOver() {
        ImportBatch batch = aBatch(document());
        assertThat(extractionProgress.claim(batch.getId())).contains("UPLOADED");
        assertThat(stateOf(batch.getId())).isEqualTo("EXTRACTING");

        assertThatThrownBy(() -> importService.extractCandidates(batch.getId()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already being extracted");
        assertThat(extractionProgress.claim(batch.getId())).as("a second claim is refused").isEmpty();

        jdbc.update("UPDATE import_batch SET extraction_started_at = now() - interval '31 minutes' WHERE id = ?", batch.getId());
        assertThat(importService.extractCandidates(batch.getId())).hasSize(8);
        assertThat(stateOf(batch.getId())).isEqualTo("EXTRACTED");
    }

    @Test
    void VYB0940_AC37_theBatchViewShowsAFailedExtractionAndItsReasonAndExtractingFromTheScreenContinuesIt() throws Exception {
        var admin = jwt().jwt(j -> j.subject("sub-adm").claim("email", "adm@it.test").claim("preferred_username", "adm").claim("azp", "vyoog-web"));
        UUID adminUser = users.upsert("sub-adm", "adm@it.test", "adm").getId();
        grants.grant(adminUser, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, adminUser);
        ImportBatch batch = aBatch(document());
        FAIL_TRIAGE_ON.set(TRIAGE_CALLS.get() + 2);

        mvc.perform(post("/api/v1/import/batches/" + batch.getId() + "/extract").with(admin))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Document analysis failed")));
        mvc.perform(get("/api/v1/import/batches/" + batch.getId()).with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("EXTRACTION_FAILED")).andExpect(jsonPath("$.extractionError").isNotEmpty());

        FAIL_TRIAGE_ON.set(0);
        jdbc.update("UPDATE import_batch SET extraction_started_at = NULL WHERE id = ?", batch.getId());
        Thread.sleep(5_200); // the controller paces extraction requests per person
        mvc.perform(post("/api/v1/import/batches/" + batch.getId() + "/extract").with(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8));
        mvc.perform(get("/api/v1/import/batches/" + batch.getId()).with(admin))
            .andExpect(jsonPath("$.state").value("EXTRACTED")).andExpect(jsonPath("$.extractionError").doesNotExist());
    }

    @Test
    void VYB0940_AC38_aCandidatesChecksAndTraceProposalsMakeTheirModelCallsOutsideATransaction() {
        ImportBatch batch = aBatch(document());
        ImportCandidate candidate = importService.extractCandidates(batch.getId()).get(0);
        double before = callsInTransaction();

        ImportCandidate linted = importService.lint(candidate.getId());
        ImportCandidate proposed = importService.proposeTraceLinks(candidate.getId());

        assertThat(linted.getQualityScore()).isNotNull();
        assertThat(proposed.getFlags()).contains("proposedTraceLinks").contains("traceModel");
        assertThat(callsInTransaction()).isEqualTo(before);
    }

    @Test
    void VYB0940_AC39_aWriteDefersItsEmbeddingToAfterTheCommitAndItStillArrives() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        double before = callsInTransaction();

        var r = newRequirement(p, author);
        waitForEmbedding(r.getId(), 1);
        var revised = requirementService.update(r.getId(), r.getRevision(), r.getTitle(), "The system shall now answer within 2 seconds.",
            r.getType(), r.getPriority(), r.getCapabilityId(), author);

        assertThat(revised.getRevision()).isEqualTo(2);
        waitForEmbedding(r.getId(), 2);
        assertThat(callsInTransaction()).as("neither the create's nor the revision's model calls were inside a transaction").isEqualTo(before);
    }

    private void waitForEmbedding(UUID requirementId, int revision) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer found = jdbc.queryForObject("SELECT count(*) FROM requirement_embedding WHERE requirement_id = ? AND revision = ?",
                Integer.class, requirementId, revision);
            if (found != null && found > 0) return;
            Thread.sleep(100);
        }
        throw new AssertionError("no embedding for revision " + revision + " of " + requirementId + " arrived within 10 seconds");
    }

    @Test
    void VYB0940_AC40_anAttachmentUploadWritesItsRowsAfterTheFileAndEachVersionHasItsOwnKey() {
        Portfolio p = newPortfolio();
        var r = newRequirement(p, newUser("author"));
        UUID actor = newUser("uploader");

        var first = attachmentService.upload(r.getId(), "notes.txt", "text/plain", new byte[] {1, 2}, actor);
        var second = attachmentService.upload(r.getId(), "notes.txt", "text/plain", new byte[] {3}, actor);

        assertThat(second.attachment().getId()).as("the same filename is the same attachment").isEqualTo(first.attachment().getId());
        assertThat(first.version().getVersion()).isEqualTo((short) 1);
        assertThat(second.version().getVersion()).isEqualTo((short) 2);
        assertThat(second.version().getStorageKey()).isNotEqualTo(first.version().getStorageKey());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_version WHERE attachment_id = ?", Integer.class, first.attachment().getId())).isEqualTo(2);
    }
}
