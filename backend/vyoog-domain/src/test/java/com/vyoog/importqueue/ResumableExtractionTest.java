package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.DocumentFinding;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.ai.TraceRelationClassifier;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.trace.TraceGraphService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * VYB-0940 (F31): how {@link ImportService#extractCandidates} runs an AI extraction now that it opens no transaction around the
 * model calls: one extraction of a batch at a time, a failure recorded with its reason and the steps kept, the candidates written
 * together at the end. The pipeline itself is {@link ResumableAnalysisTest}.
 */
class ResumableExtractionTest {

    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
    private final ExtractionProgress progress = mock(ExtractionProgress.class);
    private final ExtractionSteps steps = mock(ExtractionSteps.class);
    private final UUID batchId = UUID.randomUUID();
    private ImportBatch batch;
    private ImportService service;

    private static final DocumentFinding FINDING = new DocumentFinding("PROBLEM", "A shortfall goes uncounted.",
        "the shortfall is never counted", "paragraph 1", "HIGH");

    @BeforeEach
    void setUp() {
        service = new ImportService(batches, candidates, List.of(new FreeformDocumentParser()), mock(CapabilityRepository.class),
            mock(SimilaritySearchService.class), mock(RequirementService.class), mock(TraceGraphService.class), mock(AuditService.class),
            new ObjectMapper(), mock(TraceRelationClassifier.class), mock(AcceptanceCriterionService.class), mock(RequirementRepository.class),
            analysis, new com.vyoog.importqueue.prd.PrdTemplateParser(), new com.vyoog.importqueue.prd.PrdTemplateResolver(null, null, null),
            TransactionOperations.withoutTransaction(), progress);
        batch = new ImportBatch("notes.txt", UUID.randomUUID(), UploadKind.FREEFORM, UUID.randomUUID(),
            "When a picker scans a short bin the screen goes blank and the shortfall is never counted.");
        lenient().when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        lenient().when(candidates.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(analysis.available()).thenReturn(true);
        lenient().when(progress.claim(batchId)).thenReturn(Optional.of("UPLOADED"));
        lenient().when(progress.steps(eq(batchId), anyString())).thenReturn(steps);
    }

    private DocumentAnalysisService.Run aRun() {
        return new DocumentAnalysisService.Run(List.of(FINDING), "A description.", List.of("picking"), List.of(), 1, 1, 0, 0, false,
            "m", 3, Map.of(), null, DocumentAnalysisService.Run.BRIEFS_UNCONFIGURED);
    }

    @Test
    void VYB0940_AC8_theStepsOfThisBatchAreHandedToThePipelineAndSuccessCompletesTheExtraction() {
        DocumentAnalysisService.Run run = aRun();
        when(analysis.run(eq("notes.txt"), anyList(), eq(true), eq(steps))).thenReturn(run);

        List<ImportCandidate> saved = service.extractCandidates(batchId);

        assertThat(saved).hasSize(1);
        assertThat(batch.getState()).isEqualTo("EXTRACTED");
        verify(analysis).save(eq(batchId), eq(run), any());
        verify(progress).complete(batchId);
        verify(progress, never()).fail(any(), any(), any());
    }

    @Test
    void VYB0940_AC9_aSecondRequestWhileOneIsRunningIsRefusedAndNothingIsCalled() {
        when(progress.claim(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.extractCandidates(batchId))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already being extracted");

        verify(analysis, never()).run(any(), anyList(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void VYB0940_AC10_aFailureIsRecordedWithItsReasonAndThePreviousStateAndNoCandidateIsMade() {
        when(analysis.run(any(), anyList(), eq(true), any())).thenThrow(new AiProviderUnavailableException("The AI token budget for today is used up"));

        assertThatThrownBy(() -> service.extractCandidates(batchId)).isInstanceOf(AiProviderUnavailableException.class);

        verify(progress).fail(batchId, "The AI token budget for today is used up", "UPLOADED");
        verify(progress, never()).complete(any());
        verify(candidates, never()).save(any());
        verify(analysis, never()).save(any(), any(), any());
        assertThat(batch.getState()).as("the entity is not marked extracted").isEqualTo("UPLOADED");
    }

    @Test
    void VYB0940_AC11_aFailureWhileWritingTheCandidatesIsRecordedToo() {
        when(analysis.run(any(), anyList(), eq(true), any())).thenReturn(aRun());
        when(candidates.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.extractCandidates(batchId)).isInstanceOf(IllegalStateException.class);

        verify(progress).fail(batchId, "database down", "UPLOADED");
    }

    @Test
    void VYB0940_AC12_aReExtractionOfAnExtractedBatchPutsItsStateBackIfItFails() {
        when(progress.claim(batchId)).thenReturn(Optional.of("EXTRACTED"));
        when(analysis.run(any(), anyList(), eq(true), any())).thenThrow(new AiProviderUnavailableException("down"));

        assertThatThrownBy(() -> service.extractCandidates(batchId)).isInstanceOf(AiProviderUnavailableException.class);

        verify(progress).fail(batchId, "down", "EXTRACTED");
    }

    @Test
    void VYB0940_AC13_stepsMadeFromOtherTextAreNeverReusedBecauseTheDigestFollowsTheText() {
        when(analysis.run(any(), anyList(), eq(true), any())).thenReturn(aRun());
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);

        service.extractCandidates(batchId);
        verify(progress).steps(eq(batchId), digest.capture());
        String first = digest.getValue();

        ImportBatch changed = new ImportBatch("notes.txt", batch.getApplicationId(), UploadKind.FREEFORM, null, "Different words entirely.");
        when(batches.findById(batchId)).thenReturn(Optional.of(changed));
        service.extractCandidates(batchId);
        ArgumentCaptor<String> second = ArgumentCaptor.forClass(String.class);
        verify(progress, org.mockito.Mockito.times(2)).steps(eq(batchId), second.capture());

        assertThat(first).hasSize(64).matches("[0-9a-f]+");
        assertThat(second.getAllValues().get(1)).isNotEqualTo(first);
    }

    @Test
    void VYB0940_AC14_withNoModelConfiguredExtractionClaimsNothingAndKeepsNoSteps() {
        when(analysis.available()).thenReturn(false);

        service.extractCandidates(batchId);

        verify(progress, never()).claim(any());
        verify(progress, never()).steps(any(), any());
        assertThat(batch.getState()).isEqualTo("EXTRACTED");
    }
}
