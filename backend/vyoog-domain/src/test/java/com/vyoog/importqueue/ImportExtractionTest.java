package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.DocumentFinding;
import com.vyoog.ai.RequirementBriefAnalyst;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.ai.TraceRelationClassifier;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.trace.TraceGraphService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VYB-0667: what "Extract candidates" produces once the analysis agents are wired into
 * it. The document below is the case that matters — an ordinary narrative specification
 * that never uses the word "requirement", wrapped in the letterhead and page furniture
 * every real document carries.
 */
@ExtendWith(MockitoExtension.class)
class ImportExtractionTest {

    @Mock ImportBatchRepository batches;
    @Mock ImportCandidateRepository candidates;
    @Mock CapabilityRepository capabilities;
    @Mock SimilaritySearchService similarity;
    @Mock RequirementService requirementService;
    @Mock TraceGraphService traceGraph;
    @Mock AuditService audit;
    @Mock TraceRelationClassifier traceClassifier;
    @Mock AcceptanceCriterionService acceptanceCriteria;
    @Mock RequirementRepository requirements;
    @Mock DocumentAnalysisService analysis;

    final ObjectMapper json = new ObjectMapper();
    ImportService service;
    UUID batchId;
    ImportBatch batch;

    static final String DOC = String.join("\n\n",
        "Northwind Industrial Pvt Ltd · 22 Industrial Layout · support@northwind.example",
        "When a picker scans a bin that holds fewer units than the pick list expects, the app "
            + "currently shows an empty screen and the picker moves on, so the shortfall is never counted.",
        "Page 4 of 12");

    @BeforeEach
    void setUp() {
        service = new ImportService(batches, candidates, List.of(new FreeformDocumentParser()), capabilities,
            similarity, requirementService, traceGraph, audit, json,
            traceClassifier, acceptanceCriteria, requirements, analysis,
            // Real collaborators, not mocks: this test is about the freeform path, and
            // the template path is unreachable from a FREEFORM batch. A null here would
            // pass just as well until the day someone adds a PRD case to this class.
            new com.vyoog.importqueue.prd.PrdTemplateParser(),
            new com.vyoog.importqueue.prd.PrdTemplateResolver(null, null, null));
        batchId = UUID.randomUUID();
        batch = new ImportBatch("picking-notes.txt", UUID.randomUUID(), UploadKind.FREEFORM, null, DOC);
        lenient().when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        lenient().when(candidates.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DocumentAnalysisService.Run runOf(DocumentFinding... findings) {
        return runOf(Map.of(), null, findings);
    }

    /** A run that also produced briefs, keyed by finding index — what extraction gets when the analyst is configured. */
    private DocumentAnalysisService.Run runOf(Map<Integer, RequirementBriefAnalyst.Brief> briefs,
                                              String briefsUnavailable, DocumentFinding... findings) {
        return new DocumentAnalysisService.Run(List.of(findings),
            "The document describes a picking shortfall that is never recorded.",
            List.of("warehouse picking"), List.of(), 1, 1, 0, 2, false, "fake-model-1", 3,
            briefs, briefs.isEmpty() ? null : "fake-brief-model", briefsUnavailable);
    }

    @Test
    void VYB0667_AC1_candidatesComeFromMeaningNotFromParagraphSplitting() {
        when(analysis.available()).thenReturn(true);
        when(analysis.run(eq("picking-notes.txt"), anyList(), eq(true))).thenReturn(runOf(new DocumentFinding(
            "PROBLEM",
            "A short-picked bin is silently ignored: the screen goes blank and the shortfall is never counted.",
            "shows an empty screen and the picker moves on, so the shortfall is never counted",
            "paragraph 2", "HIGH")));

        List<ImportCandidate> extracted = service.extractCandidates(batchId);

        // One candidate — the meaning — not three, one per paragraph of the file.
        assertThat(extracted).hasSize(1);
        ImportCandidate c = extracted.get(0);
        assertThat(c.getStatement()).contains("shortfall is never counted");
        assertThat(c.getStatement()).doesNotContain("Northwind Industrial");
        // VYB-0635: the untouched source sentence is what the reviewer compares against.
        assertThat(c.getOriginalText()).isEqualTo("shows an empty screen and the picker moves on, so the shortfall is never counted");
        assertThat(c.getSourceLocation()).isEqualTo("paragraph 2");
        assertThat(c.getFlags()).contains("PROBLEM").contains("HIGH");
    }

    @Test
    void VYB0667_AC1_theDescriptionIsStoredFromTheSameRunThatMadeTheCandidates() {
        when(analysis.available()).thenReturn(true);
        DocumentAnalysisService.Run run = runOf(new DocumentFinding("PROBLEM", "A shortfall goes uncounted.",
            "the shortfall is never counted", "paragraph 2", "HIGH"));
        when(analysis.run(any(), anyList(), eq(true))).thenReturn(run);

        service.extractCandidates(batchId);

        // Same Run instance: the summary a reviewer reads and the candidates they act on
        // cannot be two different readings of the document.
        verify(analysis).save(eq(batchId), same(run), isNull());
    }

    @Test
    void VYB0667_AC4_theBriefsAuthoredStatementBecomesTheCandidateAndTheEvidenceStaysVerbatim() {
        when(analysis.available()).thenReturn(true);
        DocumentFinding finding = new DocumentFinding("PROBLEM",
            "A shortfall goes uncounted.", "the shortfall is never counted", "paragraph 2", "HIGH");
        when(analysis.run(any(), anyList(), eq(true))).thenReturn(runOf(
            Map.of(0, new RequirementBriefAnalyst.Brief(0,
                "Picking shortfall capture",
                "The system shall record a picking shortfall against the order line when a bin is short-picked.",
                "FUNCTIONAL", "HIGH",
                List.of("A short-picked line records a shortfall quantity.", "The order line is not silently skipped."),
                "Requires the picking screen to capture a shortfall quantity rather than skipping the line.",
                List.of("shortfall capture on the picking screen"), List.of("the ERP order feed"),
                List.of("What is the operator shown when the bin is empty rather than short?"),
                "NEEDS_CLARIFICATION")),
            null, finding));

        ImportCandidate c = service.extractCandidates(batchId).get(0);

        // The editable statement is the authored requirement, not the terse triage reading
        // — that reading is what made candidates read as restatements of their own quote.
        assertThat(c.getStatement()).isEqualTo(
            "The system shall record a picking shortfall against the order line when a bin is short-picked.");
        // VYB-0635 still holds: the source sentence is untouched, so the authored
        // statement can always be checked against what the document actually said.
        assertThat(c.getOriginalText()).isEqualTo("the shortfall is never counted");
        assertThat(c.getCriteriaCount()).isEqualTo((short) 2);
        // The type arrives as VYB-0666's proposal, unconfirmed — the reviewer confirms a
        // real suggestion instead of first having to ask for one, candidate by candidate.
        assertThat(c.getProposedType()).isEqualTo("FUNCTIONAL");
        assertThat(c.isTypeConfirmed()).isFalse();
        assertThat(c.getFlags())
            .contains("briefTitle").contains("Picking shortfall capture")
            .contains("briefPriority").contains("HIGH")
            .contains("briefReadiness").contains("NEEDS_CLARIFICATION")
            .contains("briefOpenQuestions").contains("bin is empty")
            .contains("briefDependsOn").contains("ERP order feed");
    }

    @Test
    void VYB0667_AC4_aCandidateWithNoBriefKeepsTheTriageReadingAndSaysWhy() {
        when(analysis.available()).thenReturn(true);
        when(analysis.run(any(), anyList(), eq(true))).thenReturn(runOf(
            Map.of(), DocumentAnalysisService.Run.BRIEFS_BUDGET,
            new DocumentFinding("PROBLEM", "A shortfall goes uncounted.",
                "the shortfall is never counted", "paragraph 2", "HIGH")));

        ImportCandidate c = service.extractCandidates(batchId).get(0);

        assertThat(c.getStatement()).isEqualTo("A shortfall goes uncounted.");
        // Principle 8: the card says the brief is missing and why. Left blank it would be
        // indistinguishable from an analyst that read this and had nothing to raise.
        assertThat(c.getFlags())
            .contains("briefUnavailable")
            .contains(DocumentAnalysisService.Run.BRIEFS_BUDGET)
            .doesNotContain("briefReadiness");
    }

    /** A candidate as extraction leaves it, then confirmed and selected — what commit actually sees. */
    private ImportCandidate committable(String flagsJson) {
        ImportCandidate c = new ImportCandidate(batchId, "f1", "The system shall record a picking shortfall.",
            "the shortfall is never counted", "paragraph 2");
        c.confirmCapability(UUID.randomUUID());
        c.select(true);
        c.setLintResult((short) 0, null, flagsJson);
        return c;
    }

    @Test
    void VYB0667_AC4_commitFillsTheRegistersTitleTypeAndPriorityFromTheBrief() {
        ImportCandidate c = committable("""
            {"briefTitle":"Picking shortfall capture","briefType":"FUNCTIONAL","briefPriority":"HIGH",
             "briefAcceptanceCriteria":["A short-picked line records a shortfall quantity."]}""");
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(c));
        Requirement created = mock(Requirement.class);
        UUID newId = UUID.randomUUID();
        when(created.getId()).thenReturn(newId);
        when(requirementService.create(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.commit(batchId, null);

        // The title is the brief's, not the "f1" tag that used to land in the register.
        verify(requirementService).create(eq("Picking shortfall capture"),
            eq("The system shall record a picking shortfall."), eq("FUNCTIONAL"), eq("HIGH"), any(), isNull());
        verify(acceptanceCriteria).add(newId, "A short-picked line records a shortfall quantity.");
    }

    @Test
    void VYB0667_AC4_aDocumentThatGivesNoPrioritySignalCommitsWithoutOneRatherThanAGuess() {
        // The analyst is told to return null rather than fill the field; commit must pass
        // that through so the register's own default stands, not write an invented value.
        ImportCandidate c = committable("""
            {"briefTitle":"Picking shortfall capture","briefType":"FUNCTIONAL","briefPriority":null}""");
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(c));
        Requirement created = mock(Requirement.class);
        when(created.getId()).thenReturn(UUID.randomUUID());
        when(requirementService.create(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.commit(batchId, null);

        verify(requirementService).create(any(), any(), eq("FUNCTIONAL"), isNull(), any(), isNull());
    }

    @Test
    void VYB0667_AC4_withoutABriefTheTagIsStillTheTitleRatherThanSomethingInventedAtCommit() {
        ImportCandidate c = committable("""
            {"briefUnavailable":"per-run-ai-budget-reached"}""");
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(c));
        Requirement created = mock(Requirement.class);
        when(created.getId()).thenReturn(UUID.randomUUID());
        when(requirementService.create(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.commit(batchId, null);

        verify(requirementService).create(eq("f1"), any(), isNull(), isNull(), any(), isNull());
    }

    @Test
    void VYB0667_AC4_extractThenCommitCarriesTypePriorityAndCriteriaWithNoProposerAgents() {
        when(analysis.available()).thenReturn(true);
        DocumentFinding finding = new DocumentFinding("BUSINESS_RULE",
            "Overtime past a threshold must alert someone.",
            "the shortfall is never counted", "paragraph 2", "HIGH");
        when(analysis.run(any(), anyList(), eq(true))).thenReturn(runOf(
            Map.of(0, new RequirementBriefAnalyst.Brief(0,
                "Overtime compliance alerting",
                "The system shall raise a labour-compliance alert when overtime exceeds 12 hours in a rolling week.",
                "COMPLIANCE", "CRITICAL",
                List.of("An alert is raised above 12 hours.", "The manager is the recipient."),
                "Requires a rolling-window calculation over timesheet data.",
                List.of("rolling-window calculation"), List.of("the payroll feed"),
                List.of("What happens when the payroll feed is unavailable?"), "NEEDS_CLARIFICATION")),
            null, finding));

        ImportCandidate extracted = service.extractCandidates(batchId).get(0);

        // Extraction filled everything the deleted agents used to be asked for, one call.
        assertThat(extracted.getProposedType()).isEqualTo("COMPLIANCE");
        assertThat(extracted.getCriteriaCount()).isEqualTo((short) 2);

        // The human's part, unchanged: confirm the capability, select, commit.
        extracted.confirmCapability(UUID.randomUUID());
        extracted.select(true);
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(extracted));
        Requirement created = mock(Requirement.class);
        UUID newId = UUID.randomUUID();
        when(created.getId()).thenReturn(newId);
        when(requirementService.create(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.commit(batchId, null);

        // Title, statement, type and priority all reach the register in one create call.
        verify(requirementService).create(
            eq("Overtime compliance alerting"),
            eq("The system shall raise a labour-compliance alert when overtime exceeds 12 hours in a rolling week."),
            eq("COMPLIANCE"), eq("CRITICAL"), any(), isNull());
        // And the criteria become real rows without anyone having confirmed a list —
        // there is no confirm control for them in the queue, so requiring one would mean
        // every imported requirement landing with none.
        verify(acceptanceCriteria).add(newId, "An alert is raised above 12 hours.");
        verify(acceptanceCriteria).add(newId, "The manager is the recipient.");
    }

    @Test
    void VYB0667_AC4_aHumanConfirmedTypeStillOverridesWhatExtractionRead() {
        ImportCandidate c = committable("""
            {"briefTitle":"Overtime compliance alerting","briefType":"COMPLIANCE","briefPriority":"CRITICAL"}""");
        c.confirmType("SECURITY"); // the reviewer disagreed with extraction
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(c));
        Requirement created = mock(Requirement.class);
        when(created.getId()).thenReturn(UUID.randomUUID());
        when(requirementService.create(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.commit(batchId, null);

        verify(requirementService).create(any(), any(), eq("SECURITY"), eq("CRITICAL"), any(), isNull());
    }

    /**
     * The regression that made extraction look broken: lint rebuilt the flags column from
     * an empty map and wrote it over everything else on the candidate. One click of
     * "Lint" erased the brief, so the acceptance criteria, title and priority were gone
     * before commit ever read them — extraction had produced them correctly and something
     * downstream deleted them.
     */
    @Test
    void VYB0667_AC4_lintLeavesTheBriefAloneInsteadOfOverwritingTheWholeFlagsColumn() {
        ImportCandidate c = committable("""
            {"briefTitle":"Overtime compliance alerting","briefType":"COMPLIANCE","briefPriority":"CRITICAL",
             "briefAcceptanceCriteria":["An alert is raised above 12 hours."],
             "briefReadiness":"NEEDS_CLARIFICATION","analysisCategory":"BUSINESS_RULE"}""");
        UUID id = UUID.randomUUID();
        when(candidates.findById(id)).thenReturn(Optional.of(c));
        when(candidates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(similarity.similarToText(any(), anyInt())).thenReturn(List.of());

        ImportCandidate linted = service.lint(id);

        assertThat(linted.getFlags())
            .contains("briefTitle").contains("Overtime compliance alerting")
            .contains("briefAcceptanceCriteria").contains("An alert is raised above 12 hours.")
            .contains("briefPriority").contains("CRITICAL")
            .contains("analysisCategory");
        // Lint's own work still lands — this is a merge, not a no-op.
        assertThat(linted.getQualityScore()).isNotNull();
    }

    @Test
    void VYB0667_AC4_reLintingStillClearsItsOwnStaleFlagsAfterTheWordingIsFixed() {
        // "promptly" flagged on the first pass; the reviewer rewrote it, so a re-lint has
        // to drop the flag rather than merge the old one forward forever.
        ImportCandidate c = committable("""
            {"briefTitle":"A title","ambiguousTerms":["promptly"],"suggestedFixText":"old fix text"}""");
        UUID id = UUID.randomUUID();
        when(candidates.findById(id)).thenReturn(Optional.of(c));
        when(candidates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(similarity.similarToText(any(), anyInt())).thenReturn(List.of());

        ImportCandidate linted = service.lint(id);

        assertThat(linted.getFlags()).doesNotContain("promptly").doesNotContain("old fix text");
        assertThat(linted.getFlags()).contains("briefTitle"); // and the brief is untouched
    }

    @Test
    void VYB0667_AC5_withNoProviderConfiguredExtractionStaysStructural() {
        when(analysis.available()).thenReturn(false);

        List<ImportCandidate> extracted = service.extractCandidates(batchId);

        // The pre-AI behaviour, unchanged: one candidate per paragraph, letterhead included.
        assertThat(extracted).hasSize(3);
        assertThat(extracted.get(0).getStatement()).contains("Northwind Industrial");
        verify(analysis, never()).run(any(), anyList());
    }

    @Test
    void VYB0667_AC5_aConfiguredProviderThatFailsIsAnErrorNotAQuietFallback() {
        when(analysis.available()).thenReturn(true);
        when(analysis.run(any(), anyList(), eq(true))).thenThrow(new AiProviderUnavailableException("provider timed out"));

        assertThatThrownBy(() -> service.extractCandidates(batchId))
            .isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("timed out");

        // Nothing was written, and it did not silently produce the weaker extraction.
        verify(candidates, never()).save(any());
        assertThat(batch.getState()).isEqualTo("UPLOADED");
    }

    @Test
    void VYB0667_AC5_aDocumentThatFailsValidationStillReportsWhichRuleItFailed() {
        ImportBatch empty = new ImportBatch("blank.txt", UUID.randomUUID(), UploadKind.FREEFORM, null, "   ");
        when(batches.findById(batchId)).thenReturn(Optional.of(empty));

        assertThatThrownBy(() -> service.extractCandidates(batchId))
            .isInstanceOf(DocumentValidationException.class);
    }
}