package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.DocumentDescriptionSynthesizer;
import com.vyoog.ai.DocumentFinding;
import com.vyoog.ai.DocumentGroundingCritic;
import com.vyoog.ai.DocumentRelevanceTriager;
import com.vyoog.ai.RequirementBriefAnalyst;
import com.vyoog.platform.audit.AuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VYB-0667. The three agents are fakes here on purpose: what needs testing is the
 * orchestration and the guards around the model, not the model. Every behaviour asserted
 * below is one this pipeline enforces in code — a live provider cannot be relied on to
 * quote accurately, stay in budget, or refuse to invent, which is exactly why the
 * service checks rather than asks.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    @Mock ImportBatchRepository batches;
    @Mock DocumentAnalysisRepository analyses;
    @Mock AiUsageTracker usage;
    @Mock AuditService audit;

    /** The real freeform parser — chunking is fed by a parser in production too. */
    final DocumentParser parser = new FreeformDocumentParser();
    final ObjectMapper json = new ObjectMapper();

    FakeTriager triager;
    FakeSynthesizer synthesizer;
    FakeCritic critic;
    FakeBriefAnalyst briefAnalyst;
    DocumentAnalysisService service;

    UUID batchId;
    UUID actor;

    /** Two paragraphs of real substance, two of the boilerplate the requirement says to ignore. */
    static final String LETTERHEAD = "Contoso Manufacturing Limited, 14 Queens Road, Bengaluru 560001. Tel +91 80 4000 1234.";
    static final String RULE = "The system shall reject a lead whose credit score is below 640 unless a manager overrides it.";
    static final String PROBLEM = "Today the nightly sync fails silently when the ERP returns a 502, and no operator is notified.";
    static final String FOOTER = "Confidential. Page 3 of 18. Copyright 2026.";

    @BeforeEach
    void setUp() {
        triager = new FakeTriager();
        synthesizer = new FakeSynthesizer();
        critic = new FakeCritic();
        briefAnalyst = new FakeBriefAnalyst();
        service = new DocumentAnalysisService(batches, analyses, List.of(parser), triager, synthesizer,
            critic, briefAnalyst, usage, audit, json);
        batchId = UUID.randomUUID();
        actor = UUID.randomUUID();

        lenient().when(usage.tryConsume()).thenReturn(true);
        // JPA assigns the id on save; a mock doesn't, and the service reads it back for
        // the audit event. Stamping one here keeps the test honest about that ordering
        // rather than making the service tolerate an id it will always have in production.
        lenient().when(analyses.save(any())).thenAnswer(inv -> withId(inv.getArgument(0)));
        lenient().when(batches.findById(batchId)).thenReturn(Optional.of(batch(
            String.join("\n\n", LETTERHEAD, RULE, PROBLEM, FOOTER))));
    }

    private ImportBatch batch(String rawText) {
        return new ImportBatch("lead-frd.txt", UUID.randomUUID(), UploadKind.FREEFORM, actor, rawText);
    }

    // ── AC1: only meaningful content survives ─────────────────────────────────

    @Test
    void VYB0667_AC1_boilerplateIsDiscardedAndNeverReachesTheDescription() {
        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getFindingsKept()).isEqualTo(2);
        assertThat(result.getNoiseBlocksDiscarded()).isEqualTo(2);
        // The synthesiser is only ever handed the survivors — the letterhead and the
        // page footer are not in its input at all, so they cannot come back in its prose.
        assertThat(synthesizer.lastFindings).hasSize(2);
        assertThat(synthesizer.lastFindings).noneMatch(f -> f.evidence().contains("Queens Road"));
        assertThat(synthesizer.lastFindings).noneMatch(f -> f.evidence().contains("Confidential"));
    }

    @Test
    void VYB0667_AC1_aDocumentOfPureBoilerplateFailsHonestlyRatherThanInventingADescription() {
        when(batches.findById(batchId)).thenReturn(Optional.of(batch(LETTERHEAD + "\n\n" + FOOTER)));

        assertThatThrownBy(() -> service.analyse(batchId, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("administrative or boilerplate");
        assertThat(synthesizer.calls).isZero();
        verify(analyses, never()).save(any());
    }

    // ── AC2: every finding is quoted from the source ──────────────────────────

    @Test
    void VYB0667_AC2_aFindingWhoseQuoteIsNotInTheDocumentIsRejected() {
        triager.fabricate = true;

        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getFindingsRejected()).isEqualTo(1);
        assertThat(synthesizer.lastFindings)
            .noneMatch(f -> f.statement().contains("99.99% uptime"));
    }

    @Test
    void VYB0667_AC2_evidenceMatchingIgnoresReflowedWhitespaceButNotChangedValues() {
        String source = "The batch window is 45 minutes\n   and may not overlap the close.";

        assertThat(DocumentAnalysisService.evidenceOccursIn("The batch window is 45 minutes and may not", source)).isTrue();
        assertThat(DocumentAnalysisService.evidenceOccursIn("The batch window is 90 minutes and may not", source)).isFalse();
        // A quote too short to identify anything is not evidence of anything.
        assertThat(DocumentAnalysisService.evidenceOccursIn("the", source)).isFalse();
    }

    @Test
    void VYB0667_AC2_theSameClauseQuotedTwiceIsOneFinding() {
        List<DocumentFinding> twice = List.of(
            new DocumentFinding("BUSINESS_RULE", "Reading one", "credit score is below 640", "paragraph 2", "HIGH"),
            new DocumentFinding("BUSINESS_RULE", "Reading two", "Credit  score IS below 640", "paragraph 9", "MEDIUM"));

        List<DocumentFinding> deduped = DocumentAnalysisService.dedupe(twice);

        assertThat(deduped).hasSize(1);
        assertThat(deduped.get(0).sourceLocation()).isEqualTo("paragraph 2"); // first occurrence keeps its place
    }

    // ── Brief stage (agent 4) ─────────────────────────────────────────────────

    /** Fourteen distinct findings in one chunk — enough to cross the batch boundary three times. */
    private static String fourteenClauses() {
        StringBuilder doc = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            doc.append("The system shall clause number ").append(i).append(" without exception.\n\n");
        }
        return doc.toString();
    }

    /** A minimally well-formed brief — the guard tests below vary one field each, so the rest stay out of the way. */
    private static RequirementBriefAnalyst.Brief brief(int index, String statement, String readiness) {
        return new RequirementBriefAnalyst.Brief(index, "A title", statement, "FUNCTIONAL", "MEDIUM",
            List.of(), "Description.", List.of(), List.of(), List.of(), readiness);
    }

    private DocumentAnalysisService.Run runWithBriefs() {
        return service.run("lead-frd.txt", service.blocksOf(batch(String.join("\n\n", LETTERHEAD, RULE, PROBLEM, FOOTER))), true);
    }

    @Test
    void VYB0667_AC4_briefsAreOnlyProducedWhenExtractionAsksForThem() {
        // The re-analysis path refreshes a description; paying for per-candidate briefs
        // there would spend the budget on output nothing reads.
        service.analyse(batchId, actor);

        assertThat(briefAnalyst.calls).isZero();
        DocumentAnalysisService.Run run = runWithBriefs();
        assertThat(briefAnalyst.calls).isEqualTo(1);
        assertThat(run.briefs()).hasSize(2);
    }

    @Test
    void VYB0667_AC4_theAnalystSeesFindingsTogetherAndTheDocumentDescription() {
        runWithBriefs();

        // Both findings in one call — the analyst can relate them, which the triager,
        // seeing one chunk at a time, structurally cannot.
        assertThat(briefAnalyst.batchesSeen).hasSize(1);
        assertThat(briefAnalyst.batchesSeen.get(0)).hasSize(2);
        assertThat(briefAnalyst.lastDescriptionSeen).isEqualTo("A description built from 2 findings.");
    }

    @Test
    void VYB0667_AC4_findingsAreBatchedSoALongDocumentDoesNotCostACallPerFinding() {
        DocumentAnalysisService.Run run = service.run("big.txt", service.blocksOf(batch(fourteenClauses())), true);

        // 14 findings at a batch size of 6 is three calls, not fourteen.
        assertThat(run.briefs()).hasSize(14);
        assertThat(briefAnalyst.calls).isEqualTo(3);
    }

    @Test
    void VYB0667_AC4_aBriefWithAnIndexOutsideItsBatchIsDroppedNotAttachedToTheWrongFinding() {
        briefAnalyst.nextReply = List.of(
            brief(0, "The system shall do the first thing.", "CLEAR"),
            brief(99, "The system shall do a thing nobody asked about.", "CLEAR"));

        DocumentAnalysisService.Run run = runWithBriefs();

        assertThat(run.briefs()).hasSize(1);
        assertThat(run.briefs()).containsOnlyKeys(0);
        assertThat(run.briefsUnavailable()).isEqualTo(DocumentAnalysisService.Run.BRIEFS_MALFORMED);
    }

    @Test
    void VYB0667_AC4_aBriefMissingOnlyItsTitleStillDeliversItsCriteriaAndStatement() {
        // Losing the whole brief over an absent title would cost the statement, the
        // criteria and the open questions — commit falls back to the tag for a title,
        // and there is no fallback for the rest.
        briefAnalyst.nextReply = List.of(new RequirementBriefAnalyst.Brief(0, null,
            "The system shall do the thing.", "FUNCTIONAL", "HIGH",
            List.of("A checkable condition."), "Description.",
            List.of(), List.of(), List.of(), "CLEAR"));

        DocumentAnalysisService.Run run = runWithBriefs();

        assertThat(run.briefs()).containsKey(0);
        assertThat(run.briefs().get(0).validTitle()).isNull();
        assertThat(run.briefs().get(0).acceptanceCriteria()).containsExactly("A checkable condition.");
    }

    @Test
    void VYB0667_AC4_aBriefWithAReadinessOutsideTheClosedSetIsDropped() {
        briefAnalyst.nextReply = List.of(
            brief(0, "The system shall do the thing.", "TWO_WEEKS"));

        DocumentAnalysisService.Run run = runWithBriefs();

        assertThat(run.briefs()).isEmpty();
        assertThat(run.briefsUnavailable()).isEqualTo(DocumentAnalysisService.Run.BRIEFS_MALFORMED);
    }

    @Test
    void VYB0667_AC4_theSameIndexTwiceKeepsTheFirstRatherThanSilentlyPreferringTheLast() {
        briefAnalyst.nextReply = List.of(
            brief(0, "First reading.", "CLEAR"),
            brief(0, "Contradictory second reading.", "UNDERSPECIFIED"));

        DocumentAnalysisService.Run run = runWithBriefs();

        assertThat(run.briefs().get(0).statement()).isEqualTo("First reading.");
    }

    @Test
    void VYB0667_AC4_anUnconfiguredAnalystNamesItselfRatherThanLeavingCandidatesLookingUnanalysed() {
        briefAnalyst.available = false;

        DocumentAnalysisService.Run run = runWithBriefs();

        assertThat(run.briefs()).isEmpty();
        assertThat(run.briefModel()).isNull();
        assertThat(run.briefsUnavailable()).isEqualTo(DocumentAnalysisService.Run.BRIEFS_UNCONFIGURED);
        assertThat(briefAnalyst.calls).isZero();
    }

    @Test
    void VYB0667_AC4_runningOutOfBudgetMidBriefingIsReportedAsBudgetNotAsNothingToSay() {
        // One chunk of triage, synthesis and critique are not metered here; the budget
        // runs out on the second of the three brief batches.
        when(usage.tryConsume()).thenReturn(true, true, false);
        when(usage.used()).thenReturn(3);
        when(usage.limit()).thenReturn(2);

        DocumentAnalysisService.Run run = service.run("big.txt", service.blocksOf(batch(fourteenClauses())), true);

        assertThat(run.briefs()).hasSize(6).doesNotContainKey(13);
        assertThat(run.briefsUnavailable()).isEqualTo(DocumentAnalysisService.Run.BRIEFS_BUDGET);
    }

    // ── AC3: a proposal, never an application ─────────────────────────────────

    @Test
    void VYB0667_AC3_aFreshAnalysisIsProposedAndDecidedByNobody() {
        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getState()).isEqualTo("PROPOSED");
        assertThat(result.getDecidedBy()).isNull();
        assertThat(result.getDecidedAt()).isNull();
    }

    @Test
    void VYB0667_AC3_dismissalWithoutAReasonIsRefused() {
        assertThatThrownBy(() -> service.dismiss(UUID.randomUUID(), actor, "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");
        verify(analyses, never()).save(any());
    }

    @Test
    void VYB0667_AC3_anAlreadyDecidedAnalysisCannotBeDecidedTwice() {
        DocumentAnalysis proposed = service.analyse(batchId, actor);
        UUID id = UUID.randomUUID();
        when(analyses.findById(id)).thenReturn(Optional.of(proposed));

        service.accept(id, actor);

        assertThat(proposed.getState()).isEqualTo("ACCEPTED");
        assertThat(proposed.getDecidedBy()).isEqualTo(actor);
        assertThatThrownBy(() -> service.dismiss(id, actor, "changed my mind"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already accepted");
    }

    // ── AC4: unsupported claims are surfaced, and the loop is bounded ─────────

    @Test
    void VYB0667_AC4_anUngroundedDraftIsRevisedOnceAndTheCriticIsToldWhatToFix() {
        critic.unsupportedFirstPass = List.of("The system processes 10,000 leads per hour.");

        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.isRevisionRan()).isTrue();
        assertThat(synthesizer.calls).isEqualTo(2);
        assertThat(synthesizer.lastGuidance).containsExactly("The system processes 10,000 leads per hour.");
        assertThat(result.getUnsupportedClaims()).isEqualTo("[]"); // the second pass cleared them
    }

    @Test
    void VYB0667_AC4_claimsTheRevisionCannotFixAreStoredRatherThanHidden() {
        critic.unsupportedFirstPass = List.of("Throughput doubled after the change.");
        critic.unsupportedSecondPass = List.of("Throughput doubled after the change.");

        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getUnsupportedClaims()).contains("Throughput doubled after the change.");
        // Bounded: one revision, not a loop that keeps paying for the same argument.
        assertThat(synthesizer.calls).isEqualTo(2);
        assertThat(critic.calls).isEqualTo(2);
    }

    // ── AC5: coverage is reported honestly ────────────────────────────────────

    @Test
    void VYB0667_AC5_aRunStoppedByTheAiBudgetIsRecordedAsPartial() {
        String longDoc = longDocumentOf(6);
        when(batches.findById(batchId)).thenReturn(Optional.of(batch(longDoc)));
        // Budget for two chunks, then exhausted.
        when(usage.tryConsume()).thenReturn(true, true, false);

        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getChunksTotal()).isGreaterThan(2);
        assertThat(result.getChunksAnalysed()).isEqualTo(2);
        assertThat(result.isPartial()).isTrue();
    }

    @Test
    void VYB0667_AC5_aRunThatReadEverythingIsNotMarkedPartial() {
        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getChunksAnalysed()).isEqualTo(result.getChunksTotal());
        assertThat(result.isPartial()).isFalse();
    }

    @Test
    void VYB0667_AC5_chunkingNeverSplitsABlockAndEveryChunkNamesWhereItCameFrom() {
        List<ExtractedCandidate> blocks = parser.parse(longDocumentOf(6));

        List<DocumentAnalysisService.Chunk> chunks = DocumentAnalysisService.chunk(blocks);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.location()).isNotBlank();
            assertThat(c.text().length()).isLessThanOrEqualTo(
                DocumentAnalysisService.CHUNK_TARGET_CHARS + longestBlock(blocks));
        });
        // Nothing was lost between the parser and the chunks.
        String all = chunks.stream().map(DocumentAnalysisService.Chunk::text).reduce("", (a, b) -> a + "\n\n" + b);
        assertThat(blocks).allSatisfy(b -> assertThat(all).contains(b.text().strip()));
    }

    // ── AC6: provenance ───────────────────────────────────────────────────────

    @Test
    void VYB0667_AC6_everyRunRecordsItsModelPromptVersionAndCallCount() {
        DocumentAnalysis result = service.analyse(batchId, actor);

        assertThat(result.getModel()).isEqualTo("fake-model-1");
        assertThat(result.getPromptVersion()).isEqualTo(DocumentAnalysisService.PROMPT_VERSION);
        // one triage call for the single chunk, one synthesis, one critique
        assertThat(result.getAiCalls()).isEqualTo(3);
    }

    @Test
    void VYB0667_AC6_theRunIsAudited() {
        service.analyse(batchId, actor);

        verify(audit).record(eq(actor), eq("import.document_analysed"), eq("IMPORT_BATCH"), eq(batchId),
            isNull(), anyMap());
    }

    @Test
    void VYB0667_AC6_providerFailurePropagatesInsteadOfProducingAnEmptyDescription() {
        triager.fail = true;

        assertThatThrownBy(() -> service.analyse(batchId, actor))
            .isInstanceOf(AiProviderUnavailableException.class);
        verify(analyses, never()).save(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DocumentAnalysis withId(DocumentAnalysis a) throws Exception {
        if (a.getId() == null) {
            var field = DocumentAnalysis.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(a, UUID.randomUUID());
        }
        return a;
    }

    private static int longestBlock(List<ExtractedCandidate> blocks) {
        return blocks.stream().mapToInt(b -> b.text().length()).max().orElse(0);
    }

    /** Paragraphs of real sentences, long enough to force multi-chunk behaviour. */
    private static String longDocumentOf(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= paragraphs; i++) {
            sb.append("Section ").append(i).append(". ");
            sb.append(("The system shall record every state change for section " + i + " with an audit event. ")
                .repeat(40));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** Keeps the two substantive paragraphs, discards the two boilerplate ones. */
    static final class FakeTriager implements DocumentRelevanceTriager {
        boolean fabricate;
        boolean fail;
        int calls;

        @Override
        public Triage triage(String chunkText, String sourceLocation) {
            calls++;
            if (fail) throw new AiProviderUnavailableException("provider down");

            List<DocumentFinding> findings = new ArrayList<>();
            int discarded = 0;
            if (chunkText.contains("credit score is below 640")) {
                findings.add(new DocumentFinding("BUSINESS_RULE",
                    "Lead acceptance is gated on a credit score threshold with a manager override.",
                    "reject a lead whose credit score is below 640", sourceLocation, "HIGH"));
            }
            if (chunkText.contains("nightly sync fails silently")) {
                findings.add(new DocumentFinding("PROBLEM",
                    "The ERP sync fails without notifying anyone when the upstream returns a 502.",
                    "the nightly sync fails silently when the ERP returns a 502", sourceLocation, "HIGH"));
            }
            if (chunkText.contains("Queens Road")) discarded++;
            if (chunkText.contains("Confidential")) discarded++;
            // One distinct finding per "shall clause number N" in the chunk. Distinct
            // matters: dedupe keys on the evidence, so fourteen copies of one sentence
            // are one finding — a batching test needs fourteen genuinely different ones,
            // each quoting text that really occurs, since evidenceOccursIn checks.
            java.util.regex.Matcher clauses = java.util.regex.Pattern
                .compile("shall clause number \\d+").matcher(chunkText);
            while (clauses.find()) {
                findings.add(new DocumentFinding("SYSTEM_BEHAVIOUR",
                    "A numbered clause applies.", clauses.group(), sourceLocation, "MEDIUM"));
            }
            if (chunkText.contains("Section 1")) {
                findings.add(new DocumentFinding("SYSTEM_BEHAVIOUR", "Every state change is audited.",
                    "shall record every state change", sourceLocation, "MEDIUM"));
            }
            if (fabricate) {
                findings.add(new DocumentFinding("CONSTRAINT",
                    "The platform must sustain 99.99% uptime.",
                    "the platform must sustain 99.99% uptime at all times", sourceLocation, "HIGH"));
            }
            return new Triage(List.copyOf(findings), discarded, discarded == 0 ? "" : "letterhead and page footer");
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String modelName() {
            return "fake-model-1";
        }
    }

    static final class FakeSynthesizer implements DocumentDescriptionSynthesizer {
        int calls;
        List<DocumentFinding> lastFindings = List.of();
        List<String> lastGuidance = List.of();

        @Override
        public Synthesis synthesize(String filename, List<DocumentFinding> findings, List<String> revisionGuidance) {
            calls++;
            lastFindings = findings;
            lastGuidance = revisionGuidance;
            return new Synthesis("A description built from " + findings.size() + " findings.",
                List.of("lead intake", "erp integration"));
        }

        @Override
        public String modelName() {
            return "fake-model-1";
        }
    }

    /**
     * Returns a well-formed brief per finding by default. The individual tests reach in
     * and make it misbehave — short replies, bad indexes, duplicates, a banned effort
     * word — because those are the failures the service has to survive, and a real
     * provider cannot be asked to produce them on demand.
     */
    static final class FakeBriefAnalyst implements RequirementBriefAnalyst {
        int calls;
        boolean available = true;
        final List<List<DocumentFinding>> batchesSeen = new ArrayList<>();
        String lastDescriptionSeen;
        /** Overrides what the next call returns; null means "one good brief per finding". */
        List<Brief> nextReply;

        @Override
        public List<Brief> analyse(String filename, String documentDescription, List<DocumentFinding> findings) {
            calls++;
            batchesSeen.add(findings);
            lastDescriptionSeen = documentDescription;
            if (nextReply != null) {
                List<Brief> reply = nextReply;
                nextReply = null;
                return reply;
            }
            List<Brief> out = new ArrayList<>();
            for (int i = 0; i < findings.size(); i++) {
                out.add(new Brief(i,
                    "Clause " + i + " handling",
                    "The system shall " + findings.get(i).category().toLowerCase(Locale.ROOT) + " correctly.",
                    "BUSINESS_RULE", "HIGH", List.of("A condition that can be checked."),
                    "What this asks for, in two sentences.",
                    List.of("a calculation", "a screen"), List.of("the ERP feed"),
                    List.of("What happens when the feed is down?"), "NEEDS_CLARIFICATION"));
            }
            return out;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String modelName() {
            return "fake-brief-model";
        }
    }

    static final class FakeCritic implements DocumentGroundingCritic {
        int calls;
        List<String> unsupportedFirstPass = List.of();
        List<String> unsupportedSecondPass = List.of();

        @Override
        public Critique critique(String description, List<DocumentFinding> findings) {
            calls++;
            return new Critique(calls == 1 ? unsupportedFirstPass : unsupportedSecondPass, "");
        }

        @Override
        public String modelName() {
            return "fake-model-1";
        }
    }
}
