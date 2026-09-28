package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** VYB-0631/0632/0638: the four parsers, as pure functions. */
class DocumentParserTest {

    @Test
    void freeformSplitsOnBlankLinesAndStripsBulletsWithoutRequiringThem() {
        var parser = new FreeformDocumentParser();
        String doc = "The system shall allow login.\n\n- The system shall log out after 30 minutes.\n\nNot a bullet either.";

        List<ExtractedCandidate> result = parser.parse(doc);

        assertThat(result).hasSize(3);
        assertThat(result.get(1).text()).isEqualTo("The system shall log out after 30 minutes.");
        assertThat(result.get(0).sourceLocation()).isEqualTo("paragraph 1");
    }

    @Test
    void freeformNeverThrowsRegardlessOfContent() {
        var parser = new FreeformDocumentParser();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void standardSpecExtractsNumberedLinesPreservingTheNumberAsTag() {
        var parser = new StandardSpecDocumentParser();
        String doc = "1 Overview text, not numbered as a requirement... wait it is.\n"
            + "1.1 The system shall reject invalid input.\n"
            + "1.2 The system shall log every rejection.\n";

        List<ExtractedCandidate> result = parser.parse(doc);

        assertThat(result).extracting(ExtractedCandidate::tag).contains("1.1", "1.2");
        assertThat(result).filteredOn(c -> c.tag().equals("1.2"))
            .extracting(ExtractedCandidate::text).containsExactly("The system shall log every rejection.");
    }

    @Test
    void standardSpecRefusesADocumentWithNoNumberedLinesAndNamesTheRule() {
        var parser = new StandardSpecDocumentParser();
        assertThatThrownBy(() -> parser.parse("Just some prose.\nNo numbering anywhere."))
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule())
            .isEqualTo("numbered-requirement-format");
    }

    @Test
    void csvExtractsTheStatementColumnByHeaderNameCaseInsensitively() {
        var parser = new CsvSpreadsheetDocumentParser();
        String csv = "ID,Statement\nR1,The system shall export invoices as PDF\nR2,The system shall archive after 90 days";

        List<ExtractedCandidate> result = parser.parse(csv);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).tag()).isEqualTo("R1");
        assertThat(result.get(0).text()).isEqualTo("The system shall export invoices as PDF");
    }

    @Test
    void csvFindsThePrdTemplateHeaderBelowBannerRows() {
        var parser = new CsvSpreadsheetDocumentParser();
        String csv = """
            Requirements,,,,,,,,,
            One row per requirement. Columns marked * are mandatory.,,,,,,,,,
            ,,,,,,,,,
            #,Ready?,Product *,App *,Capability *,Your Ref,Requirement Title *,Requirement Statement *,Type *,Priority *
            1,OK,Valam,HRI,Attendance,HR-ATT-01,Capture punch events,"The system shall capture punch events from biometric devices, mobile app, and web portal.",Functional,Critical
            """;

        List<ExtractedCandidate> result = parser.parse(csv);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tag()).isEqualTo("HR-ATT-01");
        assertThat(result.get(0).text())
            .isEqualTo("The system shall capture punch events from biometric devices, mobile app, and web portal.");
        assertThat(result.get(0).sourceLocation()).isEqualTo("row 5");
    }

    @Test
    void csvKeepsQuotedMultiLineCellsInsideOneRecord() {
        var parser = new CsvSpreadsheetDocumentParser();
        String csv = "Your Ref,Requirement Statement *,Acceptance Criteria\n"
            + "R1,\"The system shall import a CSV with quoted cells.\",\"1. First line\n2. Second line\"\n"
            + "R2,The system shall continue with the next row,\n";

        List<ExtractedCandidate> result = parser.parse(csv);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ExtractedCandidate::tag).containsExactly("R1", "R2");
        assertThat(result.get(0).text()).isEqualTo("The system shall import a CSV with quoted cells.");
    }

    @Test
    void csvUploadsArriveBase64EncodedLikeXlsxAndAreDecodedBeforeParsing() {
        var parser = new CsvSpreadsheetDocumentParser();
        String csv = "Your Ref,Requirement Statement *\nR1,The system shall decode the uploaded CSV text.";
        String stored = java.util.Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<ExtractedCandidate> result = parser.parse(stored);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tag()).isEqualTo("R1");
        assertThat(result.get(0).text()).isEqualTo("The system shall decode the uploaded CSV text.");
    }

    @Test
    void csvRefusesAHeaderWithNoRecognisedStatementColumn() {
        var parser = new CsvSpreadsheetDocumentParser();
        assertThatThrownBy(() -> parser.parse("Foo,Bar\n1,2"))
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule())
            .isEqualTo("missing-statement-column");
    }

    /** VYB-0638: a genuine .xlsx, built in memory with real Apache POI, base64-encoded exactly as ImportController does it, then read back. */
    @Test
    void xlsxExtractsTheStatementColumnFromARealWorkbook() throws java.io.IOException {
        var parser = new CsvSpreadsheetDocumentParser();
        byte[] bytes;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Requirements");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Statement");
            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("R1");
            row1.createCell(1).setCellValue("The system shall export invoices as PDF");
            var out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            bytes = out.toByteArray();
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);

        List<ExtractedCandidate> result = parser.parse(base64);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tag()).isEqualTo("R1");
        assertThat(result.get(0).text()).isEqualTo("The system shall export invoices as PDF");
    }

    /** VYB-0666: a genuine .docx, built in memory with real Apache POI, base64-encoded exactly as ImportController does it, then read back. */
    @Test
    void wordExtractsOneCandidatePerNonBlankParagraph() throws java.io.IOException {
        var parser = new WordDocumentParser();
        byte[] bytes;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var p1 = doc.createParagraph().createRun();
            p1.setText("The system shall export invoices as PDF.");
            doc.createParagraph(); // blank paragraph — must not become a candidate
            var p2 = doc.createParagraph().createRun();
            p2.setText("The system shall archive records after 90 days.");
            var out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            bytes = out.toByteArray();
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);

        List<ExtractedCandidate> result = parser.parse(base64);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).tag()).isEqualTo("p1");
        assertThat(result.get(0).text()).isEqualTo("The system shall export invoices as PDF.");
        assertThat(result.get(1).text()).isEqualTo("The system shall archive records after 90 days.");
    }

    /**
     * VYB-0666: the gap that made a real functional specification extract as nothing but
     * its headings. POI's {@code getParagraphs()} excludes everything inside a table, and
     * on the FRD this was reported against that was 51% of the document — including every
     * field rule, the state machine and the RBAC matrix.
     */
    @Test
    void wordReadsTableRowsNotJustTopLevelParagraphs() throws java.io.IOException {
        var parser = new WordDocumentParser();
        byte[] bytes;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            doc.createParagraph().createRun().setText("4. Field specification");
            var table = doc.createTable(3, 3);
            String[][] cells = {
                { "Field Name", "M/O", "Validation & Business Rules" },
                { "Lead Date", "M", "Cannot be a future date." },
                { "Duplicate Check", "—", "Warns on a matching Company Name and Phone within 90 days." },
            };
            for (int r = 0; r < cells.length; r++) {
                for (int c = 0; c < cells[r].length; c++) {
                    table.getRow(r).getCell(c).setText(cells[r][c]);
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            bytes = out.toByteArray();
        }

        List<ExtractedCandidate> result = parser.parse(java.util.Base64.getEncoder().encodeToString(bytes));

        // The heading, then one candidate per body row — the header row is labels, not content.
        assertThat(result).hasSize(3);
        assertThat(result.get(0).tag()).isEqualTo("p1");
        // Cells carry their column name: the column is what says whether a cell is a rule,
        // a data type or a remark, and a bare tab-join throws that away.
        assertThat(result.get(1).tag()).isEqualTo("t1r2");
        assertThat(result.get(1).text())
            .isEqualTo("Field Name: Lead Date | M/O: M | Validation & Business Rules: Cannot be a future date.");
        assertThat(result.get(1).sourceLocation()).isEqualTo("table 1, row 2");
        assertThat(result.get(2).text()).contains("Duplicate Check").contains("within 90 days");
    }

    @Test
    void wordTreatsAHeaderlessSingleColumnTableAsContentRatherThanLabels() throws java.io.IOException {
        var parser = new WordDocumentParser();
        byte[] bytes;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var table = doc.createTable(2, 1);
            table.getRow(0).getCell(0).setText("The system shall lock the period on approval.");
            table.getRow(1).getCell(0).setText("The system shall log every reopening.");
            var out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            bytes = out.toByteArray();
        }

        List<ExtractedCandidate> result = parser.parse(java.util.Base64.getEncoder().encodeToString(bytes));

        // A one-column table is a layout device. Treating its first row as a header would
        // silently swallow a real requirement.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).text()).isEqualTo("The system shall lock the period on approval.");
        assertThat(result.get(1).text()).isEqualTo("The system shall log every reopening.");
    }

    @Test
    void wordRefusesContentThatIsNotBase64() {
        var parser = new WordDocumentParser();
        assertThatThrownBy(() -> parser.parse("not base64 at all §§§"))
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule())
            .isEqualTo("not-base64");
    }

    @Test
    void wordRefusesABase64PayloadThatIsNotARealDocx() {
        var parser = new WordDocumentParser();
        String base64 = java.util.Base64.getEncoder().encodeToString("plain text, not a zip".getBytes());
        assertThatThrownBy(() -> parser.parse(base64))
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule())
            .isEqualTo("unreadable-docx");
    }

    @Test
    void reqIfExtractsIdentifierAndConcatenatedStringValues() {
        var parser = new ReqIfDocumentParser();
        String xml = """
            <REQ-IF>
              <CORE-CONTENT>
                <SPEC-OBJECTS>
                  <SPEC-OBJECT IDENTIFIER="OBJ-1">
                    <VALUES>
                      <ATTRIBUTE-VALUE-STRING THE-VALUE="The system shall authenticate users."/>
                    </VALUES>
                  </SPEC-OBJECT>
                  <SPEC-OBJECT IDENTIFIER="OBJ-2">
                    <VALUES>
                      <ATTRIBUTE-VALUE-STRING THE-VALUE="The system shall log out idle sessions."/>
                    </VALUES>
                  </SPEC-OBJECT>
                </SPEC-OBJECTS>
                <SPEC-RELATIONS>
                  <SPEC-RELATION>
                    <SOURCE><SPEC-OBJECT-REF>OBJ-1</SPEC-OBJECT-REF></SOURCE>
                    <TARGET><SPEC-OBJECT-REF>OBJ-2</SPEC-OBJECT-REF></TARGET>
                  </SPEC-RELATION>
                </SPEC-RELATIONS>
              </CORE-CONTENT>
            </REQ-IF>
            """;

        List<ExtractedCandidate> result = parser.parse(xml);

        assertThat(result).hasSize(2);
        var obj1 = result.stream().filter(c -> c.tag().equals("OBJ-1")).findFirst().orElseThrow();
        assertThat(obj1.text()).isEqualTo("The system shall authenticate users.");
        assertThat(obj1.relatedTags()).containsExactly("OBJ-2"); // VYB-0638 AC2
    }

    @Test
    void reqIfRefusesXmlWithNoSpecObjects() {
        var parser = new ReqIfDocumentParser();
        assertThatThrownBy(() -> parser.parse("<REQ-IF><CORE-CONTENT/></REQ-IF>"))
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule())
            .isEqualTo("no-spec-objects");
    }

    @Test
    void reqIfRefusesMalformedXmlNamingTheProblem() {
        var parser = new ReqIfDocumentParser();
        assertThatThrownBy(() -> parser.parse("<REQ-IF><unclosed>"))
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule())
            .isEqualTo("not-well-formed-xml");
    }
}
