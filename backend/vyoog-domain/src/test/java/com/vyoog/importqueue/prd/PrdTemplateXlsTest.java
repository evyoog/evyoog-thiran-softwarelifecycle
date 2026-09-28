package com.vyoog.importqueue.prd;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

/** VYB-0666: legacy binary Excel. Not a ZIP and not text, so without its own reader an .xls is refused outright. */
class PrdTemplateXlsTest {

    private final PrdTemplateParser parser = new PrdTemplateParser();

    private static final String[] HEADER = {
        "Product *", "App *", "Capability *", "Your Ref", "Requirement Title *", "Requirement Statement *",
        "Type *", "Priority *", "Acceptance Criteria"};

    private static byte[] workbook(Object[]... rows) throws Exception {
        try (HSSFWorkbook wb = new HSSFWorkbook(); var out = new ByteArrayOutputStream()) {
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

    @Test
    void VYB0666_AC1_readsTheTemplateFromALegacyXlsWorkbook() throws Exception {
        byte[] xls = workbook(HEADER, new Object[]{"Valam", "HRI", "Attendance", "HR-ATT-01",
            "Capture punch events", "The system shall capture punches.", "Functional", "Critical",
            "1. Within 60s.\n2. Records channel."});

        assertThat(parser.supports(xls)).isTrue();
        PrdRow r = parser.parse(xls).get(0);
        assertThat(r.title()).isEqualTo("Capture punch events");
        assertThat(r.type()).isEqualTo("FUNCTIONAL");
        assertThat(r.priority()).isEqualTo("CRITICAL");
        assertThat(r.acceptanceCriteria()).containsExactly("Within 60s.", "Records channel.");
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void VYB0666_AC1_anXlsIsNotMistakenForAZipOrForText() throws Exception {
        byte[] xls = workbook(HEADER, new Object[]{"P", "A", "C", "R1", "T", "S", "Functional", "Low", ""});

        // The CSV reader is the catch-all for text; an OLE2 file must not reach it, or it
        // reads as one row of binary noise and the header is never found.
        assertThat(new com.vyoog.importqueue.sheet.CsvSheetReader().supports(xls)).isFalse();
        assertThat(new com.vyoog.importqueue.sheet.XlsxSheetReader().supports(xls)).isFalse();
        assertThat(new com.vyoog.importqueue.sheet.XlsSheetReader().supports(xls)).isTrue();
    }

    @Test
    void VYB0666_AC3_keepsRowNumbersAlignedAcrossAGapInTheSheet() throws Exception {
        // A blank row must still count, or every problem below it points the author at the
        // wrong line of their spreadsheet.
        byte[] xls = workbook(HEADER,
            new Object[]{"P", "A", "C", "R1", "T", "S", "Functional", "Low", ""},
            new Object[]{},
            new Object[]{"P", "A", "C", "R2", "T2", "S2", "Functional", "Low", ""});

        assertThat(parser.parse(xls)).extracting(PrdRow::sourceRow).containsExactly(2, 4);
    }
}
