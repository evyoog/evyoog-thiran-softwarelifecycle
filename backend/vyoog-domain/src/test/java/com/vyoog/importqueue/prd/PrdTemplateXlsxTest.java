package com.vyoog.importqueue.prd;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * VYB-0666: the same template saved as .xlsx must import identically. Excel is what most
 * people will actually have, and the two formats disagree about enough — how a numeric
 * cell renders, where a comment lives — that "the .ods works" is no evidence about it.
 */
class PrdTemplateXlsxTest {

    private final PrdTemplateParser parser = new PrdTemplateParser();

    private static final String[] HEADER = {
        "#", "Ready?", "Product *", "App *", "Capability *", "Your Ref", "Requirement Title *",
        "Requirement Statement *", "Type *", "Priority *", "Acceptance Criteria", "Verification Method",
        "Owner", "Source / Requested By", "Parent Requirement", "Depends On", "Tags",
        "Regulatory Reference", "Target Release", "Notes"};

    private static byte[] workbook(Object[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Requirements");
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) continue;
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else row.createCell(c).setCellValue(v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void VYB0666_AC1_readsTheTemplateFromAnExcelWorkbook() throws Exception {
        byte[] xlsx = workbook(HEADER, new Object[]{
            1, "OK", "Valam", "Human Resource Intelligence", "Attendance", "HR-ATT-01",
            "Capture punch events", "The system shall capture punch events.", "Functional", "Critical",
            "1. Appears within 60s.\n2. Records channel.", "Test", "R. Chen", "HR Ops", "", "",
            "attendance, punch", "", "3.2", "note"});

        assertThat(parser.supports(xlsx)).isTrue();
        PrdRow r = parser.parse(xlsx).get(0);
        assertThat(r.title()).isEqualTo("Capture punch events");
        assertThat(r.type()).isEqualTo("FUNCTIONAL");
        assertThat(r.priority()).isEqualTo("CRITICAL");
        assertThat(r.acceptanceCriteria()).containsExactly("Appears within 60s.", "Records channel.");
        assertThat(r.tags()).containsExactly("attendance", "punch");
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void VYB0666_AC1_keepsALeadingZeroReferenceAsWrittenRatherThanAsANumber() throws Exception {
        // Excel stores "00123" as text but a release "3.20" as a number, and a naive
        // numeric read turns the release into "3.2" and a reference into "123".
        byte[] xlsx = workbook(HEADER, new Object[]{
            1, "OK", "P", "A", "C", "00123", "T", "S", "Functional", "High",
            "", "", "", "", "", "", "", "", "3.20", ""});

        PrdRow r = parser.parse(xlsx).get(0);
        assertThat(r.ref()).isEqualTo("00123");
        assertThat(r.targetRelease()).isEqualTo("3.20");
    }

    @Test
    void VYB0666_AC3_reportsTheSameProblemsFromXlsxAsFromOds() throws Exception {
        byte[] xlsx = workbook(HEADER, new Object[]{
            1, "", "", "A", "C", "", "T", "S", "Nonsense", "High", "", "", "", "", "", "", "", "", "", ""});

        PrdRow r = parser.parse(xlsx).get(0);
        assertThat(r.isValid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.startsWith("Product"))
                                .anyMatch(p -> p.contains("Nonsense"));
    }
}
