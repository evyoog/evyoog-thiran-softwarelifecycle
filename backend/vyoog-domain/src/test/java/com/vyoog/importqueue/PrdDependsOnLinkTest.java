package com.vyoog.importqueue;

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
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VYB-0666: a PRD template's own "Depends On" column, matched against "Your Ref", becomes
 * a real trace link once both sides have a requirement — the gap that made the trace
 * graph unable to draw a line between two requirements the sheet had explicitly related.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrdDependsOnLinkTest {

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

    private final ObjectMapper json = new ObjectMapper();
    private ImportService service;
    private UUID actor;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        service = new ImportService(batches, candidates, List.of(new FreeformDocumentParser()), capabilities,
            similarity, requirementService, traceGraph, audit, json, traceClassifier, acceptanceCriteria,
            requirements, analysis, new PrdTemplateParser(), resolver);
        actor = UUID.randomUUID();
        batchId = UUID.randomUUID();
    }

    /** Already committed, exactly the state a re-run against an already-imported batch finds — commit() must still resolve links for rows in this state, not only freshly-created ones. */
    private ImportCandidate committed(String tag, UUID requirementId, List<String> dependsOn) throws Exception {
        ImportCandidate c = new ImportCandidate(batchId, tag, "Statement for " + tag, "Statement for " + tag, "row");
        if (!dependsOn.isEmpty()) {
            c.setLintResult((short) 0, null, json.writeValueAsString(Map.of("prdDependsOn", dependsOn)));
        }
        c.select(true);
        c.markCommitted(requirementId, null);
        return c;
    }

    @Test
    void VYB0666_AC1_resolvesDependsOnIntoARealDerivesLinkBetweenTheTwoCommittedRequirements() throws Exception {
        UUID so010ReqId = UUID.randomUUID();
        UUID so011ReqId = UUID.randomUUID();
        ImportCandidate so010 = committed("SO-010", so010ReqId, List.of());
        ImportCandidate so011 = committed("SO-011", so011ReqId, List.of("SO-010"));
        when(batches.findById(batchId)).thenReturn(java.util.Optional.of(
            new ImportBatch("sheet.xlsx", UUID.randomUUID(), UploadKind.PRD_TEMPLATE, actor, "")));
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(so010, so011));

        service.commit(batchId, actor);

        verify(traceGraph).createLink(TraceObjectType.REQUIREMENT, so011ReqId, TraceObjectType.REQUIREMENT, so010ReqId,
            TraceLinkType.DERIVES, actor);
    }

    @Test
    void VYB0666_AC1_aDependsOnRefNotInThisBatchCreatesNoLinkAndDoesNotFailTheCommit() throws Exception {
        UUID so011ReqId = UUID.randomUUID();
        ImportCandidate so011 = committed("SO-011", so011ReqId, List.of("SO-999"));
        when(batches.findById(batchId)).thenReturn(java.util.Optional.of(
            new ImportBatch("sheet.xlsx", UUID.randomUUID(), UploadKind.PRD_TEMPLATE, actor, "")));
        when(candidates.findAllByBatchId(batchId)).thenReturn(List.of(so011));

        service.commit(batchId, actor);

        verify(traceGraph, never()).createLink(any(), any(), any(), any(), any(), any());
    }
}
