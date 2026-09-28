package com.vyoog.importqueue.prd;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.ai.TraceRelationClassifier;
import com.vyoog.importqueue.*;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.trace.TraceGraphService;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VYB-0666: a PRD template through {@link ImportService}. The fields the author filled in
 * must arrive on the candidate ready to import, not as proposals awaiting a confirmation
 * they already gave in the spreadsheet.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrdImportExtractionTest {

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
    private UUID batchId;

    private static final String[] HEADER = {
        "Product *", "App *", "Capability *", "Your Ref", "Requirement Title *", "Requirement Statement *",
        "Type *", "Priority *", "Acceptance Criteria", "Verification Method", "Owner",
        "Source / Requested By", "Tags", "Regulatory Reference", "Notes"};

    private static byte[] workbook(Object[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Requirements");
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) row.createCell(c).setCellValue(rows[r][c].toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void uploaded(byte[] sheet) {
        ImportBatch batch = new ImportBatch("prd.xlsx", UUID.randomUUID(), UploadKind.PRD_TEMPLATE, null,
            Base64.getEncoder().encodeToString(sheet));
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));
    }

    @BeforeEach
    void setUp() {
        service = new ImportService(batches, candidates, List.of(new FreeformDocumentParser()), capabilities,
            similarity, requirementService, traceGraph, audit, json, traceClassifier, acceptanceCriteria,
            requirements, analysis, new PrdTemplateParser(), resolver);
        batchId = UUID.randomUUID();
        when(candidates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resolver.resolve(any(), any())).thenReturn(new PrdTemplateResolver.Resolved(
            com.vyoog.requirements.Placement.capability(UUID.randomUUID()), List.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flagsOf(ImportCandidate c) throws Exception {
        return json.readValue(c.getFlags(), Map.class);
    }

    @Test
    void VYB0666_AC1_fillsTheCandidateFromTheColumnsWithoutRunningAnyAgent() throws Exception {
        uploaded(workbook(HEADER, new Object[]{"Valam", "HRI", "Attendance", "HR-ATT-01",
            "Capture punch events", "The system shall capture punch events.", "Functional", "Critical",
            "1. Within 60s.\n2. Records channel.", "Test", "R. Chen", "HR Ops", "attendance", "", "A note"}));

        List<ImportCandidate> out = service.extractCandidates(batchId);

        assertThat(out).hasSize(1);
        ImportCandidate c = out.get(0);
        assertThat(c.getStatement()).isEqualTo("The system shall capture punch events.");
        assertThat(c.getTag()).isEqualTo("HR-ATT-01");
        assertThat(flagsOf(c)).containsEntry("briefTitle", "Capture punch events")
                              .containsEntry("briefPriority", "CRITICAL");
        // The whole point of this path: no agent was asked anything.
        verifyNoInteractions(analysis);
    }

    @Test
    void VYB0666_AC1_takesTheAuthorsTypeAndCriteriaAsConfirmedNotProposed() throws Exception {
        // Principle 6 governs AI output, and none of this came from a model — the template
        // asked for these in their own columns and the author answered. Leaving them
        // unconfirmed would make every commit skip the row for a decision already made.
        uploaded(workbook(HEADER, new Object[]{"P", "A", "C", "R1", "T", "S", "Security", "High",
            "1. First.\n2. Second.", "", "", "", "", "", ""}));

        ImportCandidate c = service.extractCandidates(batchId).get(0);

        assertThat(c.getProposedType()).isEqualTo("SECURITY");
        assertThat(c.isTypeConfirmed()).isTrue();
        assertThat(c.isCapabilityConfirmed()).isTrue();
        assertThat(c.getCriteriaCount()).isEqualTo((short) 2);
        assertThat(json.readValue(c.getAcceptedCriteria(), List.class)).containsExactly("First.", "Second.");
        assertThat(c.isSelected()).isTrue();
    }

    @Test
    void VYB0666_AC3_leavesAProblemRowUntickedSoItCannotBeImportedUnexamined() throws Exception {
        uploaded(workbook(HEADER, new Object[]{"P", "A", "C", "R1", "T", "S", "Nonsense", "High",
            "", "", "", "", "", "", ""}));

        ImportCandidate c = service.extractCandidates(batchId).get(0);

        assertThat(c.isSelected()).isFalse();
        assertThat(flagsOf(c)).extractingByKey("prdProblems").asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
            .anyMatch(p -> p.contains("Nonsense"));
        assertThat(c.getProposedType()).isNull();
    }

    @Test
    void VYB0666_AC3_parksTheColumnsTheRegisterHasNoFieldForRatherThanDroppingThem() throws Exception {
        // The template asks for these, so discarding them silently would misrepresent what
        // filling the sheet in achieves.
        uploaded(workbook(HEADER, new Object[]{"P", "A", "C", "R1", "T", "S", "Functional", "Low",
            "", "Inspection", "R. Chen", "Compliance team", "gdpr, audit", "GDPR Art. 30", ""}));

        Map<String, Object> flags = flagsOf(service.extractCandidates(batchId).get(0));

        assertThat(flags).containsEntry("prdVerificationMethod", "Inspection")
                         .containsEntry("prdRequestedBy", "Compliance team")
                         .containsEntry("prdRegulatoryReference", "GDPR Art. 30")
                         .containsEntry("prdOwnerName", "R. Chen");
        assertThat(flags.get("prdTags")).isEqualTo(List.of("gdpr", "audit"));
    }

    @Test
    void VYB0666_AC3_carriesAnUnknownHierarchyNameOntoTheRowInsteadOfInventingIt() throws Exception {
        when(resolver.resolve(any(), any())).thenReturn(new PrdTemplateResolver.Resolved(
            com.vyoog.requirements.Placement.unplaced(),
            List.of("No product named \"Vallam\" — create it in Portfolio, or correct the cell.")));
        uploaded(workbook(HEADER, new Object[]{"Vallam", "A", "C", "R1", "T", "S", "Functional", "Low",
            "", "", "", "", "", "", ""}));

        ImportCandidate c = service.extractCandidates(batchId).get(0);

        assertThat(c.isSelected()).isFalse();
        assertThat(c.isCapabilityConfirmed()).isFalse();
        assertThat(flagsOf(c)).extractingByKey("prdProblems").asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
            .anyMatch(p -> p.contains("Vallam"));
    }

    @Test
    void VYB0666_AC3_refusesAnUploadThatIsNotASpreadsheetWithAMessageAboutTheKind() {
        ImportBatch batch = new ImportBatch("notes.txt", UUID.randomUUID(), UploadKind.PRD_TEMPLATE, null,
            "just some prose, never base64");
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.extractCandidates(batchId))
            .isInstanceOf(DocumentValidationException.class);
    }
}
