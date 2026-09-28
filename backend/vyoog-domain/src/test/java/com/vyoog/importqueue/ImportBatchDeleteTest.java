package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.ai.TraceRelationClassifier;
import com.vyoog.importqueue.prd.PrdTemplateParser;
import com.vyoog.importqueue.prd.PrdTemplateResolver;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.trace.TraceGraphService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** VYB-0666: removing an upload from the import queue — a real delete, since nothing outside the queue references a batch. */
@ExtendWith(MockitoExtension.class)
class ImportBatchDeleteTest {

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
    @Mock PrdTemplateResolver resolver;

    ImportService service;
    UUID actor;
    UUID batchId;
    ImportBatch batch;

    @BeforeEach
    void setUp() {
        service = new ImportService(batches, candidates, List.of(new FreeformDocumentParser()), capabilities,
            similarity, requirementService, traceGraph, audit, new ObjectMapper(), traceClassifier,
            acceptanceCriteria, requirements, analysis, new PrdTemplateParser(), resolver);
        actor = UUID.randomUUID();
        batchId = UUID.randomUUID();
        batch = new ImportBatch("Sale_Order_Requirements.xlsx", UUID.randomUUID(), UploadKind.PRD_TEMPLATE, actor, "");
    }

    @Test
    void VYB0666_AC1_deletesTheBatch() {
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));

        service.deleteBatch(batchId, actor);

        verify(batches).delete(batch);
    }

    @Test
    void VYB0666_AC1_recordsWhatWasDeletedOnTheAuditTrail() {
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));

        service.deleteBatch(batchId, actor);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(actor), eq("import.batch_deleted"), eq("IMPORT_BATCH"), eq(batchId), any(),
            details.capture());
        assertThat(details.getValue()).containsEntry("filename", "Sale_Order_Requirements.xlsx");
    }

    @Test
    void VYB0666_AC1_aMissingBatchIsNotFoundRatherThanSilentlyIgnored() {
        when(batches.findById(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBatch(batchId, actor))
            .isInstanceOf(NoSuchElementException.class);

        verify(batches, never()).delete(any());
    }
}
