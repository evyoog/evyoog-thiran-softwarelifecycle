package com.vyoog.importqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.DocumentFinding;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.RequirementBriefAnalyst;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.ai.TraceRelationClassifier;
import com.vyoog.detection.AmbiguousTermLexicon;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0630–0638: upload → extract → lint → propose a capability → (edit) → selective
 * commit. Nothing here writes a {@code requirement} row before {@link #commit} — and
 * even then, only for the candidates the caller actually selected and confirmed.
 */
@Service
public class ImportService {

    private static final double DUPLICATE_THRESHOLD = 0.85;

    /**
     * The only {@code flags} keys {@link #lint} writes, and therefore the only ones it
     * may clear. Every other key on a candidate belongs to a different step — extraction's
     * brief, the capability proposal, ReqIF relations — and a lint run must leave them
     * exactly as it found them.
     */
    private static final Set<String> LINT_OWNED_KEYS = Set.of(
        "ambiguousTerms", "ambiguousTermFixes", "suggestedFixText",
        "duplicateOfRequirementId", "duplicateOfKey", "duplicateSimilarity");

    private final ImportBatchRepository batches;
    private final ImportCandidateRepository candidates;
    private final Map<UploadKind, DocumentParser> parsers;
    private final CapabilityRepository capabilities;
    private final SimilaritySearchService similarity;
    private final RequirementService requirementService;
    private final TraceGraphService traceGraph;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TraceRelationClassifier traceClassifier;
    private final AcceptanceCriterionService acceptanceCriteria;
    private final RequirementRepository requirements;
    /** VYB-0667: the analysis agents extraction runs when they are configured. */
    private final DocumentAnalysisService documentAnalysis;
    /** VYB-0666: the deterministic reader for the standard PRD template. */
    private final com.vyoog.importqueue.prd.PrdTemplateParser prdParser;
    private final com.vyoog.importqueue.prd.PrdTemplateResolver prdResolver;

    /** VYB-0630: how many embedding-nearby existing requirements get offered to {@link #traceClassifier} for a judgment call. */
    private static final int TRACE_SHORTLIST_SIZE = 5;

    public ImportService(ImportBatchRepository batches, ImportCandidateRepository candidates,
                          List<DocumentParser> parserList, CapabilityRepository capabilities,
                          SimilaritySearchService similarity, RequirementService requirementService,
                          TraceGraphService traceGraph, AuditService audit, ObjectMapper json,
                          TraceRelationClassifier traceClassifier, AcceptanceCriterionService acceptanceCriteria,
                          RequirementRepository requirements, DocumentAnalysisService documentAnalysis,
                          com.vyoog.importqueue.prd.PrdTemplateParser prdParser,
                          com.vyoog.importqueue.prd.PrdTemplateResolver prdResolver) {
        this.batches = batches;
        this.candidates = candidates;
        this.parsers = parserList.stream().collect(Collectors.toMap(DocumentParser::kind, p -> p));
        this.capabilities = capabilities;
        this.similarity = similarity;
        this.requirementService = requirementService;
        this.traceGraph = traceGraph;
        this.audit = audit;
        this.json = json;
        this.traceClassifier = traceClassifier;
        this.acceptanceCriteria = acceptanceCriteria;
        this.requirements = requirements;
        this.documentAnalysis = documentAnalysis;
        this.prdParser = prdParser;
        this.prdResolver = prdResolver;
    }

    /**
     * VYB-0630 AC1: this and {@link #extractCandidates} are the only two steps before
     * a human ever sees anything — neither touches {@code requirement}. AC2: {@code
     * uploadKind} must be one of the four declared kinds (the enum itself enforces
     * that — an unsupported value can't reach this method at all).
     */
    @Transactional
    public ImportBatch upload(String filename, UUID applicationId, UploadKind kind, String rawText, UUID actor) {
        ImportBatch batch = batches.save(new ImportBatch(filename, applicationId, kind, actor, rawText));
        audit.record(actor, "import.uploaded", "IMPORT_BATCH", batch.getId(), null,
            Map.of("filename", filename, "kind", kind.name()));
        return batch;
    }

    /**
     * VYB-0632: repeatable (AC2) — re-running against the same raw text and kind always
     * finds the same document parser producing the same candidates.
     *
     * <p>VYB-0667: when the analysis agents are configured, extraction runs them instead
     * of splitting the document structurally. The difference matters for the documents
     * people actually have: a structural split turns every paragraph into a candidate,
     * including the letterhead, the contact block and the page footer, and it only finds
     * requirements in a document that already writes them as separate labelled
     * paragraphs. The agents read for meaning instead, so a narrative specification that
     * never says "requirement" still yields the rules, behaviours, constraints and
     * problems it describes — each candidate carrying the verbatim sentence it came from
     * as its untouched original text.
     *
     * <p>With no API key the structural parse is still what happens: a document has to
     * be extractable without a provider. But a provider that is configured and then
     * fails is an error the caller sees ({@link AiProviderUnavailableException} straight
     * through), never a quiet downgrade to the weaker extraction — a user who asked for
     * the good one is owed the failure, not a worse result that looks like success.
     */
    @Transactional
    public List<ImportCandidate> extractCandidates(UUID batchId) {
        ImportBatch batch = batches.findById(batchId).orElseThrow(NoSuchElementException::new);
        if (batch.getUploadKind() == UploadKind.PRD_TEMPLATE) {
            // The template path shares nothing with the others: it has no DocumentParser,
            // runs no agents, and produces candidates with their fields already filled in
            // from the columns rather than inferred from prose.
            return extractFromTemplate(batchId, batch);
        }
        DocumentParser parser = parsers.get(batch.getUploadKind());
        if (parser == null) {
            throw new IllegalStateException("No parser registered for " + batch.getUploadKind());
        }
        List<ExtractedCandidate> blocks = parser.parse(batch.getRawText()); // may throw DocumentValidationException
        if (blocks.isEmpty()) {
            // The freeform parser returns an empty list for a document with no readable
            // text rather than refusing it, so extraction used to "succeed" with zero
            // candidates and mark the batch EXTRACTED — a dead end with nothing on screen
            // explaining it. Failing here puts the reason in front of the user instead.
            throw new DocumentValidationException("empty-document",
                "No readable text was found in this document, so there was nothing to extract.");
        }

        List<ImportCandidate> saved = documentAnalysis.available()
            ? extractByMeaning(batchId, batch, blocks)
            : extractStructurally(batchId, blocks);

        batch.setState("EXTRACTED");
        batches.save(batch);
        return saved;
    }

    /**
     * VYB-0666: the standard PRD template, read column by column with no model involved.
     *
     * <p>Every other extraction path has to guess at what the author meant, because the
     * author wrote prose. This one does not: the template asked for the type, the
     * priority and the acceptance criteria in their own columns, and the person filling
     * it in answered. So the values arrive already <em>confirmed</em> rather than
     * proposed — Principle 6 governs AI output, and none of this is AI output. The
     * reviewer can still change any of it before commit; what they are not made to do is
     * re-approve their own spreadsheet cell by cell.
     *
     * <p>A row with problems still becomes a candidate, deselected, carrying the reason.
     * Refusing the whole upload over one bad Type cell would mean re-uploading to see
     * whether there was a second one (Principle 8).
     */
    private List<ImportCandidate> extractFromTemplate(UUID batchId, ImportBatch batch) {
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(batch.getRawText().strip());
        } catch (IllegalArgumentException notBase64) {
            throw new DocumentValidationException("not-a-spreadsheet",
                "This upload is not a spreadsheet. Choose the PRD template kind only for a .ods or .xlsx file.");
        }

        List<com.vyoog.importqueue.prd.PrdRow> rows = prdParser.parse(bytes); // may throw DocumentValidationException
        List<ImportCandidate> saved = new ArrayList<>();
        for (com.vyoog.importqueue.prd.PrdRow row : rows) {
            com.vyoog.importqueue.prd.PrdTemplateResolver.Resolved placement =
                prdResolver.resolve(row, batch.getApplicationId());

            List<String> problems = new ArrayList<>(row.problems());
            problems.addAll(placement.problems());

            // originalText is the statement exactly as the sheet has it (VYB-0635) — for
            // this kind the source *is* a single cell, so the two start out identical and
            // stay comparable after the reviewer edits the statement.
            ImportCandidate c = new ImportCandidate(batchId, tagFor(row), row.statement(), row.statement(),
                "Row " + row.sourceRow());
            c.confirmPlacement(placement.placement());
            if (row.type() != null) {
                c.confirmType(row.type());
            }
            if (!row.acceptanceCriteria().isEmpty()) {
                c.confirmAcceptanceCriteria(writeJsonList(row.acceptanceCriteria()));
                c.setCriteriaCount((short) row.acceptanceCriteria().size());
            }

            // The commit step reads title and priority from these keys, so the template
            // fills the same ones the brief stage would — one commit path, not two.
            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("briefTitle", row.title());
            flags.put("briefPriority", row.priority());
            flags.put("briefDescription", row.notes());
            flags.put("prdSourceRow", row.sourceRow());
            // Columns the register has no field for. Parked rather than dropped: the
            // author was asked for them, so silently discarding them would be a lie about
            // what the template is for. They surface on the candidate and travel no
            // further until there is somewhere real to put them.
            putIfPresent(flags, "prdVerificationMethod", row.verificationMethod());
            putIfPresent(flags, "prdRequestedBy", row.requestedBy());
            putIfPresent(flags, "prdRegulatoryReference", row.regulatoryReference());
            putIfPresent(flags, "prdOwnerName", row.owner());
            putIfPresent(flags, "prdTargetRelease", row.targetRelease());
            putIfPresent(flags, "prdParentRef", row.parentRef());
            if (!row.tags().isEmpty()) flags.put("prdTags", row.tags());
            if (!row.dependsOnRefs().isEmpty()) flags.put("prdDependsOn", row.dependsOnRefs());
            if (!problems.isEmpty()) flags.put("prdProblems", problems);
            c.setLintResult((short) row.acceptanceCriteria().size(), null, writeJson(flags));

            // A clean row is pre-selected because the author already decided it belongs;
            // a row with a problem is not, so committing the batch cannot carry it in
            // without somebody having looked at what is wrong with it.
            c.select(problems.isEmpty());
            saved.add(candidates.save(c));
        }

        long withProblems = saved.stream().filter(c -> !c.isSelected()).count();
        audit.record(batch.getUploadedBy(), "import.extracted", "IMPORT_BATCH", batchId, null,
            Map.of("kind", UploadKind.PRD_TEMPLATE.name(), "rows", saved.size(),
                   "rowsWithProblems", withProblems));
        return saved;
    }

    /** The author's own reference when they gave one — it is what they will search for. */
    private static String tagFor(com.vyoog.importqueue.prd.PrdRow row) {
        return row.ref().isEmpty() ? "Row " + row.sourceRow() : row.ref();
    }

    private static void putIfPresent(Map<String, Object> flags, String key, String value) {
        if (value != null && !value.isBlank()) {
            flags.put(key, value);
        }
    }

    /** The pre-AI path: one block, one candidate, unchanged since VYB-0632. */
    private List<ImportCandidate> extractStructurally(UUID batchId, List<ExtractedCandidate> blocks) {
        return blocks.stream()
            .map(e -> {
                ImportCandidate c = new ImportCandidate(batchId, e.tag(), e.text(), e.text(), e.sourceLocation());
                if (!e.relatedTags().isEmpty()) {
                    c.setLintResult((short) 0, null, writeJson(Map.of("reqifRelatedTags", e.relatedTags())));
                }
                return candidates.save(c);
            })
            .toList();
    }

    /**
     * VYB-0667: candidates from what the document means. The candidate's editable
     * statement is the agent's precise reading; its {@code originalText} — fixed at
     * extraction and never edited, per VYB-0635 — is the verbatim sentence that reading
     * came from, so a reviewer can always see the source next to the draft.
     *
     * <p>The description the same run produced is stored against the batch, from the
     * same findings, so the summary and the candidate list can never be two different
     * readings of one document.
     */
    private List<ImportCandidate> extractByMeaning(UUID batchId, ImportBatch batch, List<ExtractedCandidate> blocks) {
        DocumentAnalysisService.Run run = documentAnalysis.run(batch.getFilename(), blocks, true);
        documentAnalysis.save(batchId, run, batch.getUploadedBy());

        List<ImportCandidate> saved = new ArrayList<>();
        for (int i = 0; i < run.findings().size(); i++) {
            DocumentFinding f = run.findings().get(i);
            RequirementBriefAnalyst.Brief brief = run.briefs().get(i);

            // The brief's authored statement is what the reviewer edits; the triage
            // reading is the fallback when no brief was produced, never a blank card.
            // originalText stays the verbatim evidence either way (VYB-0635) — the whole
            // point of an authored statement is being able to check it against the source.
            String statement = brief != null ? brief.statement() : f.statement();

            ImportCandidate c = new ImportCandidate(
                batchId, "f" + (i + 1), statement, f.evidence(), f.sourceLocation());

            // Same jsonb the lint/duplicate/capability steps already write into, so the
            // card can show what kind of thing this is without a new column per attribute.
            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("analysisCategory", f.category());
            flags.put("analysisImportance", f.importance());
            flags.put("analysisModel", run.model());
            if (brief != null) {
                // The register's own columns, filled at extraction. Before this, an
                // AI-extracted requirement committed with its synthetic tag ("f1") as its
                // title, no priority and no criteria — a detail page of empty fields.
                // They live in flags rather than new columns for the same reason every
                // other extraction attribute does, and commit reads them from here.
                flags.put("briefTitle", brief.validTitle());
                flags.put("briefType", brief.validType());
                flags.put("briefPriority", brief.validPriority());
                flags.put("briefAcceptanceCriteria", brief.acceptanceCriteria());
                flags.put("briefDescription", brief.description());
                flags.put("briefEntails", brief.entails());
                flags.put("briefDependsOn", brief.dependsOn());
                flags.put("briefOpenQuestions", brief.openQuestions());
                flags.put("briefReadiness", brief.readiness());
                flags.put("briefModel", run.briefModel());

                // VYB-0666's own proposal is separate and still needs confirming; this
                // fills it in so the reviewer is confirming a real suggestion rather than
                // first having to ask for one per candidate. proposeType leaves it
                // unconfirmed, which is exactly the state wanted here.
                if (brief.validType() != null) c.proposeType(brief.validType());
            } else {
                // Principle 8: a candidate with no brief says so, with the reason. Left
                // blank it would be indistinguishable from a requirement the analyst read
                // and had nothing to raise about — the opposite of what happened.
                flags.put("briefUnavailable", run.briefsUnavailable());
                flags.put("briefFallbackStatement", true);
            }
            // criteriaCount rides in here rather than through setCriteriaCount, which
            // this call would immediately overwrite with its own first argument. The
            // count drives the queue's "0 criteria" attention flag, so a wrong zero here
            // marks every well-specified candidate as needing a look.
            short criteriaCount = brief == null ? 0 : (short) brief.acceptanceCriteria().size();
            c.setLintResult(criteriaCount, null, writeJson(flags));
            saved.add(candidates.save(c));
        }
        return List.copyOf(saved);
    }

    /**
     * VYB-0633: the wording lexicon (shared with the live "ambig" rule and lint
     * endpoint), a duplicate check against the real register (shared with the "dup"
     * detector's own similarity search), and a quality score computed from both —
     * never a model call, so this never needs an AI-unavailable path.
     *
     * <p>This merges into the candidate's existing {@code flags} rather than replacing
     * them, the same way {@link #proposeCapability} already does. It used to start from
     * an empty map and write the result over the whole column, which silently destroyed
     * every other key on the candidate. That was survivable when the only casualties
     * were a capability basis string and some classifier metadata; once extraction began
     * writing the title, type, priority and acceptance criteria into the same column, one
     * click of "Lint" erased the entire brief and the requirement then committed with no
     * criteria, no priority and "f1" as its title.
     *
     * <p>{@link #LINT_OWNED_KEYS} is cleared first so this stays a re-runnable check:
     * fixing an ambiguous word and re-linting has to remove the old flag, not leave it
     * behind next to a clean result.
     */
    @Transactional
    public ImportCandidate lint(UUID candidateId) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        Map<String, Object> flags = new HashMap<>(readJson(c.getFlags()));
        flags.keySet().removeAll(LINT_OWNED_KEYS);

        var wordingMatches = AmbiguousTermLexicon.findIn(c.getStatement());
        if (!wordingMatches.isEmpty()) {
            flags.put("ambiguousTerms", wordingMatches.stream().map(AmbiguousTermLexicon.Match::term).toList());
            // VYB-0663 AC1: "accept-with-fix shows the fix before applying" — the
            // actual guidance per flagged term, not just which terms were flagged.
            flags.put("ambiguousTermFixes", wordingMatches.stream()
                .collect(java.util.stream.Collectors.toMap(AmbiguousTermLexicon.Match::term, AmbiguousTermLexicon.Match::suggestion)));
            flags.put("suggestedFixText", annotatedWithFixes(c.getStatement(), wordingMatches));
        }

        var dupMatches = similarity.similarToText(c.getStatement(), 1);
        boolean isDuplicate = !dupMatches.isEmpty() && dupMatches.get(0).similarity() >= DUPLICATE_THRESHOLD;
        if (isDuplicate) {
            var best = dupMatches.get(0);
            // VYB-0637 AC1: the existing requirement is named.
            flags.put("duplicateOfRequirementId", best.requirementId().toString());
            flags.put("duplicateOfKey", best.key());
            flags.put("duplicateSimilarity", best.similarity());
        }

        int score = 100;
        score -= 10 * wordingMatches.size();
        if (c.getStatement().strip().length() < 20) score -= 20;
        if (isDuplicate) score -= 15;
        score = Math.max(0, Math.min(100, score));

        c.setLintResult(c.getCriteriaCount(), (short) score, writeJson(flags));
        return candidates.save(c);
    }

    /**
     * VYB-0634: a proposal, never a confirmation — {@link ImportCandidate
     * #proposeCapability} explicitly resets the confirmed flag even if called again.
     * AC2: the basis is a real reason, not "because the model said so" — it's the
     * literal word overlap that produced the pick.
     */
    @Transactional
    public ImportCandidate proposeCapability(UUID candidateId, UUID applicationId) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        List<Capability> options = capabilities.findAllByApplicationIdAndArchivedAtIsNull(applicationId);

        Capability best = null;
        int bestScore = 0;
        String[] words = c.getStatement().toLowerCase(Locale.ROOT).split("\\W+");
        for (Capability cap : options) {
            String name = cap.getName().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String w : words) {
                if (w.length() > 3 && name.contains(w)) score++;
            }
            if (score > bestScore) { bestScore = score; best = cap; }
        }

        if (best != null) {
            c.proposeCapability(best.getId());
            Map<String, Object> flags = readJson(c.getFlags());
            flags.put("capabilityBasis", "%d word(s) in the statement matched capability '%s'"
                .formatted(bestScore, best.getName()));
            c.setLintResult(c.getCriteriaCount(), c.getQualityScore(), writeJson(flags));
        } else {
            Map<String, Object> flags = readJson(c.getFlags());
            flags.put("capabilityBasis", "No capability name overlapped with the statement's wording.");
            c.setLintResult(c.getCriteriaCount(), c.getQualityScore(), writeJson(flags));
        }
        return candidates.save(c);
    }

    /**
     * VYB-0666: the human confirms (or overrides) the proposed type before commit will
     * use it. The AI proposal it confirms now arrives from extraction's brief stage
     * rather than from a per-candidate {@code proposeType} call — see the note on
     * {@link #extractCandidates}. The gate itself is unchanged.
     */
    @Transactional
    public ImportCandidate confirmType(UUID candidateId, String type) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        c.confirmType(type);
        return candidates.save(c);
    }

    /**
     * VYB-0630 AI enrichment: the human confirms (or edits, then confirms) the
     * proposed criteria list before commit will turn it into real requirement
     * children. {@code criteriaCount} finally reflects a real list rather than
     * staying at its unused zero default.
     */
    @Transactional
    public ImportCandidate confirmAcceptanceCriteria(UUID candidateId, List<String> criteria) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        c.confirmAcceptanceCriteria(writeJsonList(criteria));
        c.setCriteriaCount((short) Math.min(criteria.size(), Short.MAX_VALUE));
        return candidates.save(c);
    }

    /**
     * VYB-0630 AI enrichment: {@link SimilaritySearchService#similarToText} finds
     * what's embedding-nearby (wording overlap); {@link #traceClassifier} decides
     * which of those, if any, is a genuine relationship worth a trace link. The two
     * are deliberately separate calls — nearby is cheap and always available,
     * "genuinely related" needs judgment an embedding distance alone can't make.
     */
    @Transactional
    public ImportCandidate proposeTraceLinks(UUID candidateId) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        List<SimilaritySearchService.Match> nearby = similarity.similarToText(c.getStatement(), TRACE_SHORTLIST_SIZE);
        // VYB-0630: the classifier judges relevance from the real statement, not the
        // title alone — Match doesn't carry statement text, so it's fetched here.
        List<TraceRelationClassifier.Candidate> shortlist = nearby.stream()
            .map(m -> new TraceRelationClassifier.Candidate(
                m.key(), requirements.findById(m.requirementId()).map(Requirement::getStatement).orElse(m.title())))
            .toList();
        List<TraceRelationClassifier.ProposedLink> proposed = traceClassifier.classify(c.getStatement(), shortlist);

        Map<String, String> keyToRequirementId = new HashMap<>();
        for (SimilaritySearchService.Match m : nearby) keyToRequirementId.put(m.key(), m.requirementId().toString());

        List<Map<String, Object>> proposedView = proposed.stream()
            .map(p -> {
                Map<String, Object> m = new HashMap<>();
                m.put("requirementId", keyToRequirementId.get(p.key()));
                m.put("key", p.key());
                m.put("linkType", p.linkType());
                m.put("rationale", p.rationale());
                return m;
            })
            .toList();

        Map<String, Object> flags = readJson(c.getFlags());
        flags.put("proposedTraceLinks", proposedView);
        flags.put("traceModel", traceClassifier.modelName());
        c.setLintResult(c.getCriteriaCount(), c.getQualityScore(), writeJson(flags));
        return candidates.save(c);
    }

    public record TraceLinkChoice(UUID requirementId, String linkType) {}

    /** VYB-0630 AI enrichment: the human confirms (or drops) which proposed links actually become trace links at commit. */
    @Transactional
    public ImportCandidate confirmTraceLinks(UUID candidateId, List<TraceLinkChoice> links) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        List<Map<String, String>> stored = links.stream()
            .map(l -> Map.of("requirementId", l.requirementId().toString(), "linkType", l.linkType()))
            .toList();
        c.confirmTraceLinks(writeJsonList(stored));
        return candidates.save(c);
    }

    /** VYB-0635 AC1/AC2: only {@code statement} moves; {@code originalText} is untouched. */
    @Transactional
    public ImportCandidate editText(UUID candidateId, String newText) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        c.editText(newText);
        return candidates.save(c);
    }

    @Transactional
    public ImportCandidate confirmCapability(UUID candidateId, UUID capabilityId) {
        return confirmPlacement(candidateId, com.vyoog.requirements.Placement.capabilityOrUnplaced(capabilityId));
    }

    /** D12: place a candidate at product, application or capability level before commit. */
    @Transactional
    public ImportCandidate confirmPlacement(UUID candidateId, com.vyoog.requirements.Placement placement) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        c.confirmPlacement(placement);
        return candidates.save(c);
    }

    /**
     * D12: places every candidate in a batch at once.
     *
     * <p>A document is normally one level throughout — a product-wide policy, or one
     * application's spec — so setting it per row would be the same choice repeated
     * dozens of times, which is how rows end up placed inconsistently by accident.
     */
    @Transactional
    public int confirmPlacementForBatch(UUID batchId, com.vyoog.requirements.Placement placement) {
        List<ImportCandidate> batch = candidates.findAllByBatchId(batchId);
        for (ImportCandidate c : batch) {
            if (c.getCommittedRequirementId() == null) c.confirmPlacement(placement);
        }
        candidates.saveAll(batch);
        return (int) batch.stream().filter(c -> c.getCommittedRequirementId() == null).count();
    }

    @Transactional
    public ImportCandidate select(UUID candidateId, boolean selected) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        c.select(selected);
        return candidates.save(c);
    }

    /** VYB-0637 AC2: recorded before commit, so a flagged duplicate can be imported anyway with a stated reason. */
    @Transactional
    public ImportCandidate setImportReason(UUID candidateId, String reason) {
        ImportCandidate c = candidates.findById(candidateId).orElseThrow(NoSuchElementException::new);
        c.setImportReason(reason);
        return candidates.save(c);
    }

    public List<ImportCandidate> forBatch(UUID batchId) {
        return candidates.findAllByBatchId(batchId);
    }

    public record CommitOutcome(UUID candidateId, boolean imported, String reason, UUID requirementId) {}

    /**
     * VYB-0636: only the selected candidates move; the rest stay in the batch (AC1).
     * VYB-0634 AC1: an unconfirmed candidate is refused, not silently skipped, so the
     * caller can tell the difference from "the user chose not to import this one."
     * VYB-0637 AC2: a flagged duplicate needs {@code importReason} to go through
     * anyway. VYB-0638 AC2: once every selected candidate in the batch has a
     * requirement, ReqIF relations between them become real trace links.
     */
    @Transactional
    public List<CommitOutcome> commit(UUID batchId, UUID actor) {
        List<ImportCandidate> selected = candidates.findAllByBatchId(batchId).stream()
            .filter(ImportCandidate::isSelected)
            .toList();

        Map<String, UUID> tagToRequirementId = new HashMap<>();
        List<CommitOutcome> outcomes = new ArrayList<>();

        for (ImportCandidate c : selected) {
            if (c.getCommittedRequirementId() != null) {
                outcomes.add(new CommitOutcome(c.getId(), true, "already committed", c.getCommittedRequirementId()));
                if (c.getTag() != null) tagToRequirementId.put(c.getTag(), c.getCommittedRequirementId());
                continue;
            }
            if (!c.isCapabilityConfirmed()) {
                outcomes.add(new CommitOutcome(c.getId(), false, "capability not confirmed", null));
                continue;
            }
            Map<String, Object> flags = readJson(c.getFlags());
            boolean flaggedDuplicate = flags.containsKey("duplicateOfRequirementId");
            if (flaggedDuplicate && (c.getImportReason() == null || c.getImportReason().isBlank())) {
                outcomes.add(new CommitOutcome(c.getId(), false, "flagged as a duplicate — needs an import reason", null));
                continue;
            }

            // VYB-0666: a confirmed type is used. An unconfirmed one falls back to what
            // extraction itself read the type to be — that is the same class of output as
            // the statement this commit already writes verbatim, and it arrives with the
            // candidate rather than from a separate propose step the reviewer invoked.
            // VYB-0666's gate is unchanged: it governs its own proposal, which still needs
            // confirming to win over this. With neither, null falls through to
            // Requirement's own FUNCTIONAL default, exactly as before.
            String type = c.isTypeConfirmed() ? c.getProposedType() : briefString(c, "briefType");

            // The title the register shows. Without a brief this is still the tag, which
            // for AI extraction means "f1" — ugly, but it is what the candidate actually
            // has, and inventing a title at commit time from a statement nobody reviewed
            // would be worse than an honest placeholder.
            String briefTitle = briefString(c, "briefTitle");
            String title = briefTitle != null ? briefTitle
                : (c.getTag() == null ? "Imported requirement" : c.getTag());

            // Null when the document gave no signal — the analyst is told not to fill it
            // to avoid an empty field, so the register's default stands rather than a
            // priority nobody stated being written as though someone had.
            String priority = briefString(c, "briefPriority");

            Requirement r = requirementService.create(title, c.getStatement(), type, priority,
                c.getPlacement(), actor);

            // Two sources, deliberately different in what they require. The human-confirmed
            // list (VYB-0630) is a separately-proposed enrichment and still needs its
            // confirmation; extraction's own criteria ride with the candidate like its
            // statement does. Confirmed wins when both exist, so confirming never yields
            // fewer criteria than not confirming.
            List<String> criteria = readAcceptedCriteria(c);
            if (criteria.isEmpty()) criteria = briefStrings(c, "briefAcceptanceCriteria");
            for (String criterion : criteria) {
                if (criterion != null && !criterion.isBlank()) {
                    acceptanceCriteria.add(r.getId(), criterion);
                }
            }

            c.markCommitted(r.getId(), c.getImportReason());
            candidates.save(c);
            if (c.getTag() != null) tagToRequirementId.put(c.getTag(), r.getId());
            outcomes.add(new CommitOutcome(c.getId(), true, null, r.getId()));
        }

        // VYB-0630 AI enrichment: only the human-confirmed trace links become real
        // ones — mirrors the ReqIF-relation resolution just below, but from an AI
        // proposal instead of an explicit SPEC-RELATION tag.
        for (ImportCandidate c : selected) {
            UUID fromId = c.getCommittedRequirementId();
            if (fromId == null) continue;
            for (Map<String, String> link : readAcceptedTraceLinks(c)) {
                String toIdText = link.get("requirementId");
                String linkTypeText = link.get("linkType");
                if (toIdText == null || linkTypeText == null) continue;
                try {
                    UUID toId = UUID.fromString(toIdText);
                    if (toId.equals(fromId)) continue;
                    traceGraph.createLink(TraceObjectType.REQUIREMENT, fromId, TraceObjectType.REQUIREMENT, toId,
                        TraceLinkType.valueOf(linkTypeText), actor);
                } catch (Exception ignored) {
                    // Already linked, a bad id/type slipped through, or the target
                    // requirement no longer exists — not fatal to the commit itself,
                    // same tolerance the ReqIF-relation resolution below already has.
                }
            }
        }

        // VYB-0638 AC2: resolve ReqIF SPEC-RELATION tags into real trace links, now
        // that every candidate in this pass has (or already had) a requirement id.
        for (ImportCandidate c : selected) {
            UUID fromId = c.getCommittedRequirementId();
            if (fromId == null) continue;
            Map<String, Object> flags = readJson(c.getFlags());
            @SuppressWarnings("unchecked")
            List<String> relatedTags = (List<String>) flags.getOrDefault("reqifRelatedTags", List.of());
            for (String relatedTag : relatedTags) {
                UUID toId = tagToRequirementId.get(relatedTag);
                if (toId != null && !toId.equals(fromId)) {
                    try {
                        traceGraph.createLink(TraceObjectType.REQUIREMENT, fromId, TraceObjectType.REQUIREMENT, toId,
                            TraceLinkType.SATISFIES, actor);
                    } catch (Exception ignored) {
                        // Already linked, or one side didn't resolve — not fatal to the commit itself.
                    }
                }
            }
        }

        // VYB-0666: resolve the template's own Depends On column into real trace links,
        // the same way ReqIF's SPEC-RELATION tags resolve just above — matched by
        // tagToRequirementId, which every committed candidate in this batch already
        // populated under its Your Ref. Without this, "Depends On" was read at
        // extraction, shown on the candidate, and then went nowhere: nothing downstream
        // — the trace graph, the coverage matrix, Delivery — ever knew two requirements
        // were related, because no row was ever written to say so.
        for (ImportCandidate c : selected) {
            UUID fromId = c.getCommittedRequirementId();
            if (fromId == null) continue;
            Map<String, Object> flags = readJson(c.getFlags());
            @SuppressWarnings("unchecked")
            List<String> dependsOnRefs = (List<String>) flags.getOrDefault("prdDependsOn", List.of());
            for (String ref : dependsOnRefs) {
                UUID toId = tagToRequirementId.get(ref);
                if (toId != null && !toId.equals(fromId)) {
                    try {
                        // "This depends on that" reads as "this derives from that" —
                        // the dependent requirement's own trace link points at the one
                        // it needs, not the reverse.
                        traceGraph.createLink(TraceObjectType.REQUIREMENT, fromId, TraceObjectType.REQUIREMENT, toId,
                            TraceLinkType.DERIVES, actor);
                    } catch (Exception ignored) {
                        // Already linked, the referenced Your Ref wasn't in this batch
                        // (or wasn't selected), or one side didn't resolve — not fatal to
                        // the commit itself, same tolerance every other resolution here has.
                    }
                }
            }
        }

        audit.record(actor, "import.committed", "IMPORT_BATCH", batchId, null,
            Map.of("imported", outcomes.stream().filter(CommitOutcome::imported).count(),
                   "skipped", outcomes.stream().filter(o -> !o.imported()).count()));
        return outcomes;
    }

    public List<ImportBatch> listBatches() {
        return batches.findAllByOrderByUploadedAtDesc();
    }

    public ImportBatch getBatch(UUID id) {
        return batches.findById(id).orElseThrow(NoSuchElementException::new);
    }

    /**
     * VYB-0666: removes an upload from the import queue.
     *
     * <p>A real delete, not a soft one — unlike a requirement, nothing outside the import
     * queue ever references a batch or its candidates (no brief, baseline or trace link
     * points at one), so there is no history that a hard delete would orphan. The queue is
     * staging: what matters once a candidate is committed is the requirement it became,
     * which lives in its own table and is untouched by this.
     *
     * <p>Candidates cascade at the database level ({@code ON DELETE CASCADE}), so this is
     * one statement rather than a delete-children-then-parent dance.
     */
    @Transactional
    public void deleteBatch(UUID id, UUID actor) {
        ImportBatch batch = batches.findById(id).orElseThrow(NoSuchElementException::new);
        batches.delete(batch);
        audit.record(actor, "import.batch_deleted", "IMPORT_BATCH", id, null,
            Map.of("filename", batch.getFilename(), "state", batch.getState()));
    }

    /**
     * VYB-0663 AC1: a mechanical, honest "fix" — every flagged term gets the
     * lexicon's own guidance appended inline, in brackets, right where it occurs.
     * This is not a rewritten, grammatically-finished statement (the lexicon's
     * suggestions are guidance like "state a specific time budget," not literal
     * replacement words) — it's the fix the human still has to apply, shown at the
     * point that needs it, which is what "shows the fix before applying" asks for.
     */
    private String annotatedWithFixes(String statement, List<AmbiguousTermLexicon.Match> matches) {
        String annotated = statement;
        for (var match : matches) {
            annotated = annotated.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(match.term()) + "\\b",
                java.util.regex.Matcher.quoteReplacement(match.term()) + " [" + match.suggestion() + "]");
        }
        return annotated;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        if (value == null) return new HashMap<>();
        try {
            return new HashMap<>(json.readValue(value, Map.class));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** VYB-0630 AI enrichment: {@code ImportCandidate.acceptedCriteria}/{@code acceptedTraceLinks} are plain JSON arrays, not objects — {@link #writeJson}'s Map shape doesn't fit. */
    private String writeJsonList(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * One string the brief stage wrote into {@code flags} at extraction, or null. Blank
     * counts as absent: a title of "" would commit as a titleless requirement that looks
     * deliberate rather than falling back to the tag.
     */
    private String briefString(ImportCandidate c, String key) {
        Object value = readJson(c.getFlags()).get(key);
        if (!(value instanceof String s) || s.isBlank()) return null;
        return s.strip();
    }

    /** One string list the brief stage wrote into {@code flags}; empty when absent or the wrong shape. */
    private List<String> briefStrings(ImportCandidate c, String key) {
        Object value = readJson(c.getFlags()).get(key);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private List<String> readAcceptedCriteria(ImportCandidate c) {
        if (c.getAcceptedCriteria() == null) return List.of();
        try {
            return json.readValue(c.getAcceptedCriteria(), json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> readAcceptedTraceLinks(ImportCandidate c) {
        if (c.getAcceptedTraceLinks() == null) return List.of();
        try {
            return json.readValue(c.getAcceptedTraceLinks(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
