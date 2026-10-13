package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.importqueue.prd.PrdRow;
import com.vyoog.importqueue.prd.PrdTemplateParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

/**
 * VYB-0957 (F25, D44): Word and ReqIF imports read from structure, the two templates, and the file-level rules, with no database.
 * Everything goes through {@link PrdTemplateParser}, the one reader the spreadsheet import already uses.
 */
class ImportFormatsTest {

    private final PrdTemplateParser parser = new PrdTemplateParser();
    private final ImportTemplateService templates = new ImportTemplateService();

    // ------------------------------------------------------------------ builders

    private static byte[] docx(boolean coverTable, String[][] table, String... prose) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            for (String p : prose) doc.createParagraph().createRun().setText(p);
            if (coverTable) {
                XWPFTable cover = doc.createTable(2, 2);
                cover.getRow(0).getCell(0).setText("Version");
                cover.getRow(0).getCell(1).setText("Date");
                cover.getRow(1).getCell(0).setText("1.0");
                cover.getRow(1).getCell(1).setText("2026-10-01");
            }
            if (table != null) {
                XWPFTable t = doc.createTable(table.length, table[0].length);
                for (int r = 0; r < table.length; r++) for (int c = 0; c < table[r].length; c++) {
                    String[] lines = table[r][c].split("\n");
                    var cell = t.getRow(r).getCell(c);
                    cell.setText(lines[0]);
                    for (int i = 1; i < lines.length; i++) cell.addParagraph().createRun().setText(lines[i]);
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static final String[] WORD_HEAD = {"Your Ref", "Product *", "App *", "Capability *", "Requirement Title *", "Requirement Statement *", "Type *", "Priority *", "Acceptance Criteria", "Depends On"};

    private static String reqif(String objects, String relations) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <REQ-IF xmlns="http://www.omg.org/spec/ReqIF/20110401/reqif.xsd" xmlns:xhtml="http://www.w3.org/1999/xhtml">
              <THE-HEADER><REQ-IF-HEADER IDENTIFIER="h"><TITLE>Export</TITLE></REQ-IF-HEADER></THE-HEADER>
              <CORE-CONTENT><REQ-IF-CONTENT>
                <DATATYPES>
                  <DATATYPE-DEFINITION-ENUMERATION IDENTIFIER="dt-prio" LONG-NAME="Priority"><SPECIFIED-VALUES>
                    <ENUM-VALUE IDENTIFIER="ev-high" LONG-NAME="High"/><ENUM-VALUE IDENTIFIER="ev-low" LONG-NAME="Low"/>
                  </SPECIFIED-VALUES></DATATYPE-DEFINITION-ENUMERATION>
                </DATATYPES>
                <SPEC-TYPES><SPEC-OBJECT-TYPE IDENTIFIER="t1" LONG-NAME="Requirement"><SPEC-ATTRIBUTES>
                  <ATTRIBUTE-DEFINITION-STRING IDENTIFIER="a-name" LONG-NAME="ReqIF.Name"/>
                  <ATTRIBUTE-DEFINITION-XHTML IDENTIFIER="a-text" LONG-NAME="ReqIF.Text"/>
                  <ATTRIBUTE-DEFINITION-STRING IDENTIFIER="a-type" LONG-NAME="Type"/>
                  <ATTRIBUTE-DEFINITION-ENUMERATION IDENTIFIER="a-prio" LONG-NAME="Priority"/>
                </SPEC-ATTRIBUTES></SPEC-OBJECT-TYPE></SPEC-TYPES>
                <SPEC-OBJECTS>%s</SPEC-OBJECTS>
                <SPEC-RELATIONS>%s</SPEC-RELATIONS>
              </REQ-IF-CONTENT></CORE-CONTENT>
            </REQ-IF>
            """.formatted(objects, relations);
    }

    private static String object(String id, String name, String xhtml, String type, String prioEnum) {
        StringBuilder v = new StringBuilder("<VALUES>");
        if (name != null) v.append("<ATTRIBUTE-VALUE-STRING THE-VALUE=\"").append(name).append("\"><DEFINITION><ATTRIBUTE-DEFINITION-STRING-REF>a-name</ATTRIBUTE-DEFINITION-STRING-REF></DEFINITION></ATTRIBUTE-VALUE-STRING>");
        if (xhtml != null) v.append("<ATTRIBUTE-VALUE-XHTML><DEFINITION><ATTRIBUTE-DEFINITION-XHTML-REF>a-text</ATTRIBUTE-DEFINITION-XHTML-REF></DEFINITION><THE-VALUE><xhtml:div>").append(xhtml).append("</xhtml:div></THE-VALUE></ATTRIBUTE-VALUE-XHTML>");
        if (type != null) v.append("<ATTRIBUTE-VALUE-STRING THE-VALUE=\"").append(type).append("\"><DEFINITION><ATTRIBUTE-DEFINITION-STRING-REF>a-type</ATTRIBUTE-DEFINITION-STRING-REF></DEFINITION></ATTRIBUTE-VALUE-STRING>");
        if (prioEnum != null) v.append("<ATTRIBUTE-VALUE-ENUMERATION><DEFINITION><ATTRIBUTE-DEFINITION-ENUMERATION-REF>a-prio</ATTRIBUTE-DEFINITION-ENUMERATION-REF></DEFINITION><VALUES><ENUM-VALUE-REF>").append(prioEnum).append("</ENUM-VALUE-REF></VALUES></ATTRIBUTE-VALUE-ENUMERATION>");
        return "<SPEC-OBJECT IDENTIFIER=\"" + id + "\" TYPE=\"t1\">" + v + "</VALUES></SPEC-OBJECT>";
    }

    private static String relation(String id, String from, String to) {
        return "<SPEC-RELATION IDENTIFIER=\"" + id + "\"><SOURCE><SPEC-OBJECT-REF>" + from + "</SPEC-OBJECT-REF></SOURCE><TARGET><SPEC-OBJECT-REF>" + to + "</SPEC-OBJECT-REF></TARGET></SPEC-RELATION>";
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ Word

    @Test
    void VYB0957_AC1_aWordTableIsReadByItsHeadingsAndAMultiLineCellKeepsEachLine() throws Exception {
        byte[] file = docx(false, new String[][] {WORD_HEAD.clone(),
            {"R-1", "Vyoog", "Req", "Import", "Refuse a bad file", "The system shall refuse an unreadable file.", "functional", "High", "Refused\nReason shown", "R-0"}},
            "Introduction paragraph that is not a requirement.");

        List<PrdRow> rows = parser.parse(file);

        assertThat(rows).hasSize(1);
        PrdRow r = rows.get(0);
        assertThat(r.ref()).isEqualTo("R-1");
        assertThat(r.title()).isEqualTo("Refuse a bad file");
        assertThat(r.type()).isEqualTo("FUNCTIONAL");
        assertThat(r.priority()).isEqualTo("HIGH");
        assertThat(r.acceptanceCriteria()).containsExactly("Refused", "Reason shown");
        assertThat(r.dependsOnRefs()).containsExactly("R-0");
        assertThat(r.problems()).isEmpty();
        assertThat(parser.formatOf(file)).isEqualTo("docx");
    }

    @Test
    void VYB0957_AC2_proseIsNeverReadAndACoverTableBeforeTheRequirementsIsPassedOver() throws Exception {
        byte[] file = docx(true, new String[][] {WORD_HEAD.clone(),
            {"R-1", "Vyoog", "Req", "Import", "T", "The system shall do a thing.", "DATA", "LOW", "", ""}},
            "The system shall also do something that is written only in prose.");

        List<PrdRow> rows = parser.parse(file);

        assertThat(rows).extracting(PrdRow::ref).containsExactly("R-1");
        assertThat(rows.get(0).statement()).doesNotContain("prose");
    }

    @Test
    void VYB0957_AC3_aWordFileWithNoTableOrNoRecognisedHeadingsIsRefusedWithTheReason() throws Exception {
        assertThatThrownBy(() -> parser.parse(docx(false, null, "Only prose here.")))
            .isInstanceOf(DocumentValidationException.class).hasMessageContaining("no table")
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("no-table");
        assertThatThrownBy(() -> parser.parse(docx(true, new String[][] {{"Colour", "Size"}, {"red", "big"}})))
            .isInstanceOf(DocumentValidationException.class).hasMessageContaining("2 tables")
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("no-table");
    }

    @Test
    void VYB0957_AC4_aWordTableMissingAColumnNamesItAndStillNeedsTheHierarchyColumns() throws Exception {
        String[] head = {"Product *", "App *", "Requirement Title *", "Requirement Statement *", "Type *", "Priority *"};
        assertThatThrownBy(() -> parser.parse(docx(false, new String[][] {head, {"P", "A", "T", "S", "DATA", "LOW"}})))
            .isInstanceOf(DocumentValidationException.class).hasMessageContaining("Word table is missing required column: capability")
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("missing-columns");
    }

    // ------------------------------------------------------------------ ReqIF

    @Test
    void VYB0957_AC5_aReqIfObjectIsReadByAttributeNameWithItsIdentifierXhtmlEnumerationAndRelations() {
        String file = reqif(
            object("REQ-1", "Lock the account", "<xhtml:p>The system shall lock an account</xhtml:p><xhtml:p>after five failed sign-ins.</xhtml:p>", "SECURITY", "ev-high")
                + object("REQ-2", "Show the reason", "The system shall show why.", "functional", "ev-low"),
            relation("rel-1", "REQ-2", "REQ-1"));

        List<PrdRow> rows = parser.parse(bytes(file));

        assertThat(parser.formatOf(bytes(file))).isEqualTo("reqif");
        assertThat(rows).extracting(PrdRow::ref).containsExactly("REQ-1", "REQ-2");
        PrdRow first = rows.get(0);
        assertThat(first.title()).isEqualTo("Lock the account");
        assertThat(first.statement()).isEqualTo("The system shall lock an account\nafter five failed sign-ins.");
        assertThat(first.type()).isEqualTo("SECURITY");
        assertThat(first.priority()).as("an enumeration is the long name of the value chosen").isEqualTo("HIGH");
        assertThat(rows.get(1).dependsOnRefs()).as("a relation is the source's Depends On").containsExactly("REQ-1");
        assertThat(first.dependsOnRefs()).isEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.problems()).as("no hierarchy columns are asked of a ReqIF file").isEmpty());
        assertThat(rows).allSatisfy(r -> assertThat(r.product()).isEmpty());
    }

    @Test
    void VYB0957_AC6_aReqIfWithoutATypeOrPriorityAttributeIsRefusedNotDefaulted() {
        String xml = reqif(object("REQ-1", "A title", "A statement.", null, null), "")
            .replace("<ATTRIBUTE-DEFINITION-STRING IDENTIFIER=\"a-type\" LONG-NAME=\"Type\"/>", "")
            .replace("<ATTRIBUTE-DEFINITION-ENUMERATION IDENTIFIER=\"a-prio\" LONG-NAME=\"Priority\"/>", "");

        assertThatThrownBy(() -> parser.parse(bytes(xml)))
            .isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining("no attribute named type, priority")
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("missing-columns");
    }

    @Test
    void VYB0957_AC7_aReqIfRowMissingAValueIsAProblemOnThatRowAndAHeadingOnlyObjectIsOne() {
        String file = reqif(object("REQ-1", "Has everything", "A statement.", "DATA", "ev-low")
            + object("REQ-2", "No priority", "A statement.", "DATA", null)
            + object("REQ-3", "A chapter heading", null, "DATA", "ev-high")
            + object("REQ-4", null, null, null, null), "");

        List<PrdRow> rows = parser.parse(bytes(file));

        assertThat(rows).extracting(PrdRow::ref).as("an object with no title and no statement is not a requirement").containsExactly("REQ-1", "REQ-2", "REQ-3");
        assertThat(rows.get(0).problems()).isEmpty();
        assertThat(rows.get(1).problems()).containsExactly("Priority is required");
        assertThat(rows.get(2).problems()).containsExactly("Requirement statement is required");
    }

    @Test
    void VYB0957_AC8_aReqIfzArchiveIsReadLikeTheFileInsideIt() throws Exception {
        byte[] xml = bytes(reqif(object("REQ-1", "In a zip", "A statement.", "DATA", "ev-low"), ""));
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("export/spec.reqif"));
            zip.write(xml);
            zip.closeEntry();
        }

        assertThat(parser.formatOf(out.toByteArray())).isEqualTo("reqif");
        assertThat(parser.parse(out.toByteArray())).extracting(PrdRow::title).containsExactly("In a zip");

        var empty = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(empty)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write(bytes("nothing"));
            zip.closeEntry();
        }
        assertThat(parser.formatOf(empty.toByteArray())).as("a zip with no .reqif in it is not claimed").isNull();
    }

    @Test
    void VYB0957_AC9_aReqIfThatIsNotWellFormedHasNoObjectsOrDeclaresAnEntityIsRefused() {
        String broken = reqif(object("REQ-1", "x", "y", "DATA", "ev-low"), "").replace("</REQ-IF>", "");
        assertThatThrownBy(() -> parser.parse(bytes(broken))).isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("not-well-formed-xml");
        assertThatThrownBy(() -> parser.parse(bytes(reqif("", "")))).isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("no-spec-objects");
        String xxe = reqif(object("REQ-1", "x", "y", "DATA", "ev-low"), "").replace("<REQ-IF ", "<!DOCTYPE REQ-IF [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><REQ-IF ");
        assertThatThrownBy(() -> parser.parse(bytes(xxe))).isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("not-well-formed-xml");
    }

    // ------------------------------------------------------------------ detection and limits

    @Test
    void VYB0957_AC10_eachFormatIsRecognisedByItsContentAndCsvStillCatchesPlainText() throws Exception {
        assertThat(parser.formatOf(templates.xlsx())).isEqualTo("xlsx");
        assertThat(parser.formatOf(templates.docx())).isEqualTo("docx");
        assertThat(parser.formatOf(bytes("Product,App,Capability,Requirement Title\nP,A,C,T\n"))).isEqualTo("csv");
        assertThat(parser.formatOf(bytes("<html><body>not reqif</body></html>"))).as("XML that is not ReqIF falls to the text reader, which then finds no header").isEqualTo("csv");
        assertThat(parser.formatOf(new byte[] {1, 2, 3, 0, 0})).isNull();
    }

    @Test
    void VYB0957_AC11_moreRowsThanTheLimitAreRefusedWithTheCount() {
        StringBuilder csv = new StringBuilder("Product,App,Capability,Requirement Title,Requirement Statement,Type,Priority\n");
        for (int i = 0; i <= PrdTemplateParser.MAX_ROWS; i++) csv.append("P,A,C,T").append(i).append(",S").append(i).append(",DATA,LOW\n");
        assertThatThrownBy(() -> parser.parse(bytes(csv.toString()))).isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining("5001 requirements").hasMessageContaining("at most 5000")
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("too-many-rows");
    }

    // ------------------------------------------------------------------ the templates

    @Test
    void VYB0957_AC12_theExcelTemplateHasTheRequiredHeadingsFirstDropdownsAndAnInstructionsSheetAndNoExampleToImport() throws Exception {
        byte[] file = templates.xlsx();
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(wb.getSheetName(0)).as("the import reads the first sheet").isEqualTo("Requirements");
            assertThat(wb.getSheetName(1)).isEqualTo("Instructions");
            var header = wb.getSheetAt(0).getRow(1);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Product *");
            assertThat(header.getLastCellNum()).isEqualTo((short) ImportTemplateService.COLUMNS.size());
            List<? extends DataValidation> validations = wb.getSheetAt(0).getDataValidations();
            assertThat(validations).hasSize(2);
            assertThat(validations.get(0).getValidationConstraint().getExplicitListValues()).contains("FUNCTIONAL", "COMPLIANCE");
            assertThat(validations.get(1).getValidationConstraint().getExplicitListValues()).containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW");
        }
        assertThatThrownBy(() -> parser.parse(file)).as("blank template: its columns are found, and there is nothing to import")
            .isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("no-rows");
    }

    @Test
    void VYB0957_AC13_aFilledExcelTemplateImportsWithEveryColumnOfTheTemplateRead() throws Exception {
        byte[] blank = templates.xlsx();
        byte[] filled;
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(blank)); var out = new ByteArrayOutputStream()) {
            var row = wb.getSheetAt(0).getRow(2) != null ? wb.getSheetAt(0).getRow(2) : wb.getSheetAt(0).createRow(2);
            var cols = ImportTemplateService.COLUMNS;
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).example().isEmpty()) continue;
                row.createCell(i).setCellValue(cols.get(i).example());
            }
            wb.write(out);
            filled = out.toByteArray();
        }

        List<PrdRow> rows = parser.parse(filled);

        assertThat(rows).hasSize(1);
        PrdRow r = rows.get(0);
        assertThat(r.problems()).as("the example in the instructions is itself a valid row").isEmpty();
        assertThat(r.product()).isEqualTo("Vyoog");
        assertThat(r.type()).isEqualTo("FUNCTIONAL");
        assertThat(r.acceptanceCriteria()).hasSize(2);
        assertThat(r.dependsOnRefs()).containsExactly("REQ-099", "REQ-098");
        assertThat(r.tags()).containsExactly("import", "validation");
    }

    @Test
    void VYB0957_AC14_theWordTemplateHasTheHeadingsInATableAndAFilledRowImports() throws Exception {
        byte[] blank = templates.docx();
        try (var doc = new XWPFDocument(new ByteArrayInputStream(blank))) {
            var table = doc.getTables().get(0);
            assertThat(table.getRow(0).getTableCells()).extracting(c -> c.getText())
                .containsExactly("Your Ref", "Product *", "App *", "Capability *", "Requirement Title *", "Requirement Statement *", "Type *", "Priority *", "Acceptance Criteria", "Depends On");
            assertThat(doc.getDocument().getBody().getSectPr().getPgSz().getOrient().toString()).isEqualTo("landscape");
        }
        assertThatThrownBy(() -> parser.parse(blank)).isInstanceOf(DocumentValidationException.class)
            .extracting(e -> ((DocumentValidationException) e).getFailedRule()).isEqualTo("no-rows");

        byte[] filled;
        try (var doc = new XWPFDocument(new ByteArrayInputStream(blank)); var out = new ByteArrayOutputStream()) {
            var row = doc.getTables().get(0).getRow(1);
            String[] values = {"R-7", "Vyoog", "Requirements", "Import", "Title", "The system shall work.", "REPORT", "CRITICAL", "Works", ""};
            for (int i = 0; i < values.length; i++) row.getCell(i).setText(values[i]);
            doc.write(out);
            filled = out.toByteArray();
        }
        List<PrdRow> rows = parser.parse(filled);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).problems()).isEmpty();
        assertThat(rows.get(0).priority()).isEqualTo("CRITICAL");
    }
}
