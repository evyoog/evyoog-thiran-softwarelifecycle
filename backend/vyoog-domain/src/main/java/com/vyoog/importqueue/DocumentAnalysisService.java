package com.vyoog.importqueue;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0667: the multi-agent pipeline that reads an imported document and proposes a
 * description of what it is actually about.
 *
 * <p>Three agents, each with one job, run in a fixed order rather than a free-running
 * loop:
 *
 * <ol>
 *   <li><b>Triage</b>, once per chunk — keeps only material that carries meaning and
 *       discards the letterhead, the contact block, the version table and the rest of
 *       the administrative surround. Everything it keeps is quoted from the chunk.
 *   <li><b>Synthesis</b>, once — writes the description from the surviving findings.
 *       It never sees the document, so noise dropped in triage cannot come back.
 *   <li><b>Critique</b>, once — names every claim in the draft that the findings do not
 *       support. If it finds any, synthesis runs a second and final time with that list,
 *       and the critic checks the result once more.
 *   <li><b>Brief</b>, once per batch of {@value #BRIEF_BATCH_SIZE} findings, and only
 *       when extraction asks for it — turns each surviving finding into an authored
 *       statement plus what building it entails, what it depends on, and what the
 *       document never answers. The three agents above answer "what is in this
 *       document"; this one answers "what would it mean to build this", which is the
 *       question the person working the import queue is actually holding.
 * </ol>
 *
 * <p>Two guards are deliberately code, not prompt text, because a prompt is a request
 * and this is a requirement:
 *
 * <ul>
 *   <li>A finding whose {@code evidence} does not occur in the chunk it came from is
 *       dropped ({@link #evidenceOccursIn}). A model that paraphrases or invents its
 *       quote loses the finding, and the count of what was dropped is stored.
 *   <li>The revision loop runs at most once. "Critique until grounded" is how an agent
 *       loop burns a budget on a claim it will never be able to support; whatever
 *       survives is stored and shown to the reviewer instead.
 * </ul>
 *
 * <p>Nothing here writes a requirement, a candidate or a document record. The run ends
 * as a PROPOSED row a person accepts or dismisses (Principle 7).
 */
@Service
public class DocumentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisService.class);

    /**
     * VYB-0616/0651: bumped whenever a prompt below changes, so a stored description can
     * be read against the instructions that produced it rather than today's.
     */
    static final String PROMPT_VERSION = "doc-analysis/1";

    /**
     * Chunking is by character budget on paragraph boundaries. Big enough that a rule and
     * the paragraph qualifying it usually land in the same chunk — the triage agent can
     * only see relationships inside the chunk it is given — and small enough to stay well
     * inside the model's input window with the system prompt attached.
     */
    static final int CHUNK_TARGET_CHARS = 6000;

    /**
     * A document shorter than this is one chunk regardless: splitting a two-page note in
     * half costs an extra call and loses the only context it had.
     */
    static final int MIN_SPLIT_CHARS = 8000;

    /**
     * How many findings the brief analyst is handed per call. Large enough that the
     * analyst can see related findings together and that a long document doesn't spend
     * its whole AI budget here; small enough that one malformed reply costs a handful of
     * briefs rather than the document's.
     */
    static final int BRIEF_BATCH_SIZE = 6;

    private final ImportBatchRepository batches;
    private final DocumentAnalysisRepository analyses;
    private final Map<UploadKind, DocumentParser> parsers;
    private final DocumentRelevanceTriager triager;
    private final DocumentDescriptionSynthesizer synthesizer;
    private final DocumentGroundingCritic critic;
    private final RequirementBriefAnalyst briefAnalyst;
    private final AiUsageTracker usage;
    private final AuditService audit;
    private final ObjectMapper json;

    public DocumentAnalysisService(ImportBatchRepository batches, DocumentAnalysisRepository analyses,
                                   List<DocumentParser> parserList, DocumentRelevanceTriager triager,
                                   DocumentDescriptionSynthesizer synthesizer, DocumentGroundingCritic critic,
                                   RequirementBriefAnalyst briefAnalyst,
                                   AiUsageTracker usage, AuditService audit, ObjectMapper json) {
        this.batches = batches;
        this.analyses = analyses;
        this.parsers = parserList.stream().collect(Collectors.toMap(DocumentParser::kind, p -> p));
        this.triager = triager;
        this.synthesizer = synthesizer;
        this.critic = critic;
        this.briefAnalyst = briefAnalyst;
        this.usage = usage;
        this.audit = audit;
        this.json = json;
    }

    /** Whether the agents can run at all; false means extraction stays structural. */
    public boolean available() {
        return triager.available();
    }

    @Transactional(readOnly = true)
    public DocumentAnalysis current(UUID batchId) {
        return analyses.findFirstByBatchIdOrderByCreatedAtDesc(batchId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<DocumentAnalysis> history(UUID batchId) {
        return analyses.findAllByBatchIdOrderByCreatedAtDesc(batchId);
    }

    /**
     * Runs the pipeline over a batch's stored raw text and saves the description as a
     * proposal.
     *
     * @throws AiProviderUnavailableException if the provider is unconfigured or fails —
     *     propagated rather than turned into an empty description, so the caller can say
     *     what actually went wrong (the same choice {@code ImportService#proposeType} makes)
     * @throws IllegalStateException if the batch has no text to analyse
     */
    /**
     * What one run produced, before anything is persisted: the findings extraction turns
     * into candidates, and the description that explains what the document is about.
     */
    public record Run(
        List<DocumentFinding> findings, String description, List<String> themes,
        List<String> unsupportedClaims, int chunksTotal, int chunksAnalysed,
        int findingsRejected, int noiseBlocksDiscarded, boolean revisionRan,
        String model, int aiCalls,
        /**
         * Briefs keyed by their finding's index in {@link #findings}. Sparse on purpose:
         * a finding with no entry did not get one, and {@link #briefsUnavailable} says
         * why. Extraction writes that reason onto the candidate rather than leaving a
         * blank space that reads like "nothing to say" (Principle 8).
         */
        Map<Integer, RequirementBriefAnalyst.Brief> briefs,
        String briefModel,
        String briefsUnavailable) {

        /** Reasons a finding can come back without a brief — stored verbatim on the candidate. */
        public static final String BRIEFS_NOT_REQUESTED = "not-requested";
        public static final String BRIEFS_UNCONFIGURED = "provider-unconfigured";
        public static final String BRIEFS_BUDGET = "per-run-ai-budget-reached";
        public static final String BRIEFS_MALFORMED = "model-reply-not-usable";
    }

    /**
     * Description only — the re-analysis entry point. Briefs are per-candidate and cost
     * a call per batch of findings, so a run that only refreshes the batch's description
     * does not pay for them.
     */
    public Run run(String filename, List<ExtractedCandidate> blocks) {
        return run(filename, blocks, false);
    }

    /**
     * Runs the agents over an already-parsed document and returns everything they
     * produced. Persisting is the caller's business: extraction stores this alongside the
     * candidates it creates from the same findings, so the description and the candidate
     * list can never describe different readings of the document.
     *
     * @param withBriefs run the brief analyst over the surviving findings as well. Only
     *     extraction asks for this — it is the step that turns findings into candidates,
     *     and the brief is what makes a candidate answerable rather than a restated quote.
     * @throws AiProviderUnavailableException if the provider is configured but fails —
     *     propagated, never downgraded to a silent structural fallback
     */
    public Run run(String filename, List<ExtractedCandidate> blocks, boolean withBriefs) {
        List<Chunk> chunks = chunk(blocks);
        usage.beginRun();

        List<DocumentFinding> kept = new ArrayList<>();
        int rejected = 0;
        int noiseBlocks = 0;
        int analysed = 0;
        int calls = 0;

        for (Chunk c : chunks) {
            // VYB-0620: stop at the budget instead of running the document to the end.
            // The chunks not read are reported as unread; they are not "read, nothing found".
            if (!usage.tryConsume()) {
                log.info("[ai] document analysis stopped at the per-run AI budget after {}/{} chunks",
                    analysed, chunks.size());
                break;
            }
            DocumentRelevanceTriager.Triage triage = triager.triage(c.text(), c.location());
            calls++;
            analysed++;
            noiseBlocks += triage.discardedCount();

            for (DocumentFinding f : triage.findings()) {
                if (!f.isWellFormed() || !evidenceOccursIn(f.evidence(), c.text())) {
                    rejected++;
                    continue;
                }
                kept.add(new DocumentFinding(
                    f.category(), f.statement().strip(), f.evidence().strip(), c.location(), f.importance()));
            }
        }

        if (kept.isEmpty()) {
            // Honest outcome, not an error and not an invented description: the pipeline
            // read the document and found nothing that carried meaning.
            throw new IllegalStateException(
                "Nothing in this document carried analysable meaning — "
                    + analysed + " of " + chunks.size() + " section(s) read, all of it administrative or boilerplate.");
        }

        List<DocumentFinding> deduped = dedupe(kept);

        DocumentDescriptionSynthesizer.Synthesis draft = synthesizer.synthesize(filename, deduped, List.of());
        calls++;
        DocumentGroundingCritic.Critique verdict = critic.critique(draft.description(), deduped);
        calls++;

        boolean revised = false;
        if (!verdict.grounded()) {
            draft = synthesizer.synthesize(filename, deduped, verdict.unsupportedClaims());
            verdict = critic.critique(draft.description(), deduped);
            calls += 2;
            revised = true;
        }

        Map<Integer, RequirementBriefAnalyst.Brief> briefs = new LinkedHashMap<>();
        String briefsUnavailable = null;
        if (!withBriefs) {
            briefsUnavailable = Run.BRIEFS_NOT_REQUESTED;
        } else if (!briefAnalyst.available()) {
            briefsUnavailable = Run.BRIEFS_UNCONFIGURED;
        } else {
            calls += brief(filename, draft.description(), deduped, briefs);
            if (briefs.size() < deduped.size()) {
                // Which reason applies is decided by what actually stopped it: the budget
                // check below is the only thing that can end the loop early, so anything
                // still missing after a completed loop is a reply the guards rejected.
                briefsUnavailable = usage.used() > usage.limit() ? Run.BRIEFS_BUDGET : Run.BRIEFS_MALFORMED;
            }
        }

        return new Run(deduped, draft.description(), draft.themes(), verdict.unsupportedClaims(),
            chunks.size(), analysed, rejected, noiseBlocks, revised, triager.modelName(), calls,
            Map.copyOf(briefs), briefAnalyst.available() ? briefAnalyst.modelName() : null, briefsUnavailable);
    }

    /**
     * Findings are briefed in batches of {@link #BRIEF_BATCH_SIZE}, not one call each.
     * Two reasons, and the second is the one that shows in the output: a call per finding
     * turns a 60-finding specification into 60 calls and hits the per-run budget long
     * before the document ends, and an analyst handed several findings at once can relate
     * them — which the triage agent structurally cannot, since it only ever sees one
     * chunk.
     *
     * <p>A brief whose index doesn't resolve to a finding in its own batch is dropped
     * rather than attached to whatever sat at that position. A model that returns four
     * briefs for six findings loses two briefs; it does not silently mislabel four.
     *
     * @return how many model calls this stage made
     */
    private int brief(String filename, String description, List<DocumentFinding> findings,
                      Map<Integer, RequirementBriefAnalyst.Brief> out) {
        int calls = 0;
        for (int start = 0; start < findings.size(); start += BRIEF_BATCH_SIZE) {
            if (!usage.tryConsume()) {
                log.info("[ai] requirement briefs stopped at the per-run AI budget after {}/{} findings",
                    out.size(), findings.size());
                break;
            }
            int end = Math.min(start + BRIEF_BATCH_SIZE, findings.size());
            List<DocumentFinding> slice = List.copyOf(findings.subList(start, end));
            calls++;

            for (RequirementBriefAnalyst.Brief b : briefAnalyst.analyse(filename, description, slice)) {
                if (!b.isWellFormed() || b.index() >= slice.size()) continue;
                int absolute = start + b.index();
                // First brief for an index wins: a model that returns the same index twice
                // has contradicted itself, and picking the later one is not more correct.
                out.putIfAbsent(absolute, b.at(absolute));
            }
        }
        return calls;
    }

    /** Stores a run against its batch as a proposal, and audits it. */
    @Transactional
    public DocumentAnalysis save(UUID batchId, Run run, UUID actor) {
        DocumentAnalysis saved = analyses.save(new DocumentAnalysis(
            batchId, run.description(), writeFindings(run.findings()), writeStrings(run.themes()),
            writeStrings(run.unsupportedClaims()), run.chunksTotal(), run.chunksAnalysed(),
            run.findings().size(), run.findingsRejected(), run.noiseBlocksDiscarded(),
            run.revisionRan(), run.model(), PROMPT_VERSION, run.aiCalls()));

        // A LinkedHashMap rather than Map.of: this is already at Map.of's ten-pair ceiling
        // and the brief counters below take it past. Nulls are legal here and Map.of's are not.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("analysisId", saved.getId().toString());
        after.put("state", saved.getState());
        after.put("chunksAnalysed", run.chunksAnalysed());
        after.put("chunksTotal", run.chunksTotal());
        after.put("findingsKept", run.findings().size());
        after.put("findingsRejected", run.findingsRejected());
        after.put("unsupportedClaims", run.unsupportedClaims().size());
        after.put("revisionRan", run.revisionRan());
        after.put("model", run.model());
        after.put("promptVersion", PROMPT_VERSION);
        // Both, not just the count: "12 of 14 briefed" and the reason the other two
        // weren't is the difference between a budget that needs raising and a model
        // reply that needs looking at.
        after.put("briefsProduced", run.briefs().size());
        after.put("briefsUnavailable", run.briefsUnavailable());
        audit.record(actor, "import.document_analysed", "IMPORT_BATCH", batchId, null, after);
        return saved;
    }

    /**
     * Runs the pipeline over a batch's own document and stores the result. Kept as its
     * own entry point for a re-analysis of a batch that is already extracted; the normal
     * path is extraction, which calls {@link #run} and {@link #save} together with the
     * candidates it derives from the same findings.
     *
     * @throws AiProviderUnavailableException if the provider is unconfigured or fails
     * @throws IllegalStateException if the batch has no text to analyse
     */
    @Transactional
    public DocumentAnalysis analyse(UUID batchId, UUID actor) {
        ImportBatch batch = batches.findById(batchId).orElseThrow(NoSuchElementException::new);
        return save(batchId, run(batch.getFilename(), blocksOf(batch)), actor);
    }

    /**
     * The document as blocks. Deliberately the registered parser rather than raw_text: a
     * .docx or .xlsx is stored base64-encoded (see ImportController#upload), so reading
     * the column directly would hand the model a wall of base64 and get a confidently
     * meaningless analysis back. It also means every upload kind arrives as blocks that
     * already carry a real source location.
     */
    public List<ExtractedCandidate> blocksOf(ImportBatch batch) {
        String raw = batch.getRawText();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                "This batch has no stored document text to analyse. Re-upload the file — text is captured at upload.");
        }
        DocumentParser parser = parsers.get(batch.getUploadKind());
        if (parser == null) {
            throw new IllegalStateException("No parser registered for " + batch.getUploadKind());
        }
        return parser.parse(raw);
    }

    /** Principle 7: the description becomes the batch's accepted description only here, by a person. */
    @Transactional
    public DocumentAnalysis accept(UUID analysisId, UUID actor) {
        DocumentAnalysis a = analyses.findById(analysisId).orElseThrow(NoSuchElementException::new);
        requireUndecided(a);
        a.accept(actor);
        DocumentAnalysis saved = analyses.save(a);
        audit.record(actor, "import.document_analysis_accepted", "IMPORT_BATCH", a.getBatchId(),
            Map.of("state", "PROPOSED"), Map.of("state", "ACCEPTED", "analysisId", analysisId.toString()));
        return saved;
    }

    /** VYB-0619: dismissals are stored with a reason, which is what makes them measurable later. */
    @Transactional
    public DocumentAnalysis dismiss(UUID analysisId, UUID actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A dismissal needs a reason.");
        }
        DocumentAnalysis a = analyses.findById(analysisId).orElseThrow(NoSuchElementException::new);
        requireUndecided(a);
        a.dismiss(actor, reason.strip());
        DocumentAnalysis saved = analyses.save(a);
        audit.record(actor, "import.document_analysis_dismissed", "IMPORT_BATCH", a.getBatchId(),
            Map.of("state", "PROPOSED"),
            Map.of("state", "DISMISSED", "analysisId", analysisId.toString(), "reason", reason.strip()));
        return saved;
    }

    private void requireUndecided(DocumentAnalysis a) {
        if (!"PROPOSED".equals(a.getState())) {
            throw new IllegalStateException(
                "This analysis was already " + a.getState().toLowerCase(Locale.ROOT) + ". Re-run the analysis to get a fresh proposal.");
        }
    }

    // ── the deterministic half ────────────────────────────────────────────────

    record Chunk(String text, String location) {}

    /**
     * Packs the parser's blocks up to {@link #CHUNK_TARGET_CHARS}, never splitting one:
     * a sentence cut in half is a sentence neither chunk can be asked about. A chunk's
     * location is the range its blocks came from, so every finding traces back to a
     * place in the document rather than to a character offset nobody can use.
     */
    static List<Chunk> chunk(List<ExtractedCandidate> blocks) {
        List<ExtractedCandidate> real = blocks.stream().filter(b -> b.text() != null && !b.text().isBlank()).toList();
        if (real.isEmpty()) {
            throw new IllegalStateException("This document parsed into no readable text blocks.");
        }

        int totalChars = real.stream().mapToInt(b -> b.text().length()).sum();
        if (totalChars <= MIN_SPLIT_CHARS) {
            return List.of(new Chunk(
                real.stream().map(ExtractedCandidate::text).collect(Collectors.joining("\n\n")),
                span(real.get(0), real.get(real.size() - 1))));
        }

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        ExtractedCandidate first = real.get(0);
        ExtractedCandidate last = first;
        for (ExtractedCandidate b : real) {
            if (current.length() > 0 && current.length() + b.text().length() > CHUNK_TARGET_CHARS) {
                chunks.add(new Chunk(current.toString().strip(), span(first, last)));
                current.setLength(0);
                first = b;
            }
            current.append(b.text().strip()).append("\n\n");
            last = b;
        }
        if (current.length() > 0) {
            chunks.add(new Chunk(current.toString().strip(), span(first, last)));
        }
        return List.copyOf(chunks);
    }

    private static String span(ExtractedCandidate first, ExtractedCandidate last) {
        String from = first.sourceLocation();
        String to = last.sourceLocation();
        return from.equals(to) ? from : from + " – " + to;
    }

    /**
     * The anti-fabrication check. Whitespace is normalised because a model reflowing a
     * quote across lines is a formatting difference, not a fabricated one; everything
     * else must match, including numbers, units and field names. Comparison is
     * case-insensitive for the same reason and no further.
     */
    static boolean evidenceOccursIn(String evidence, String source) {
        if (evidence == null || evidence.isBlank()) return false;
        String needle = evidence.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        String haystack = source.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return needle.length() >= 12 && haystack.contains(needle);
    }

    /**
     * The same clause quoted in two chunks — a heading repeated in a table, a rule
     * restated in a summary — is one finding, not two. Keyed on the evidence rather than
     * the statement, since two readings of the same sentence are still one source fact,
     * and the first occurrence keeps its location.
     */
    static List<DocumentFinding> dedupe(List<DocumentFinding> findings) {
        Map<String, DocumentFinding> byEvidence = new LinkedHashMap<>();
        for (DocumentFinding f : findings) {
            byEvidence.putIfAbsent(f.evidence().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT), f);
        }
        return List.copyOf(byEvidence.values());
    }

    private String writeFindings(List<DocumentFinding> findings) {
        try {
            return json.writeValueAsString(findings);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the analysis findings: " + e.getMessage(), e);
        }
    }

    private String writeStrings(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the analysis: " + e.getMessage(), e);
        }
    }
}