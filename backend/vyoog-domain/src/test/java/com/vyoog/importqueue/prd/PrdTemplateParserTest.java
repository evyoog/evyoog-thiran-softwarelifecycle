package com.vyoog.importqueue.prd;

import static org.assertj.core.api.Assertions.*;

import com.vyoog.importqueue.DocumentValidationException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** VYB-0666: the deterministic template import — same file in, same rows out, no model involved. */
class PrdTemplateParserTest {

    private final PrdTemplateParser parser = new PrdTemplateParser();

    private static byte[] sheet(String... rowsXml) {
        StringBuilder table = new StringBuilder();
        for (String r : rowsXml) table.append(r);
        String content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:spreadsheet><table:table table:name="S">%s</table:table>
              </office:spreadsheet></office:body></office:document-content>
            """.formatted(table);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder("<table:table-row>");
        for (String c : cells) {
            String v = c == null ? "" : c;
            sb.append("<table:table-cell><text:p>")
              .append(v.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "</text:p><text:p>"))
              .append("</text:p></table:table-cell>");
        }
        return sb.append("</table:table-row>").toString();
    }

    private static final String[] HEADER = {
        "#", "Ready?", "Product *", "App *", "Capability *", "Your Ref", "Requirement Title *",
        "Requirement Statement *", "Type *", "Priority *", "Acceptance Criteria", "Verification Method",
        "Owner", "Source / Requested By", "Parent Requirement", "Depends On", "Tags",
        "Regulatory Reference", "Target Release", "Notes"};

    private static String[] filled() {
        return new String[]{"1", "OK", "Valam", "HRI", "Attendance", "HR-ATT-01", "Capture punch events",
            "The system shall capture punch events.", "Functional", "Critical",
            "1. Appears within 60s.\n2. Records channel and device ID.", "Test", "R. Chen",
            "HR Operations Head", "", "HR-ATT-00, HR-ATT-09", "attendance, punch", "", "3.2", "Scope note"};
    }

    private static byte[] realTemplate() throws Exception {
        try (var in = PrdTemplateParserTest.class.getResourceAsStream("/prd/sample-prd-template.ods")) {
            return in.readAllBytes();
        }
    }

    @Test
    void VYB0666_AC1_readsTheShippedTemplateFindingItsHeaderBelowTheBannerAndLegend() throws Exception {
        List<PrdRow> rows = parser.parse(realTemplate());

        assertThat(rows).hasSize(6);
        PrdRow first = rows.get(0);
        assertThat(first.title()).isEqualTo("Capture punch events from all channels");
        assertThat(first.product()).isEqualTo("Valam");
        assertThat(first.application()).isEqualTo("Human Resource Intelligence");
        assertThat(first.capability()).isEqualTo("Attendance");
        assertThat(first.type()).isEqualTo("FUNCTIONAL");
        assertThat(first.priority()).isEqualTo("CRITICAL");
        assertThat(first.ref()).isEqualTo("HR-ATT-01");
        assertThat(first.acceptanceCriteria()).hasSizeGreaterThan(1);
        assertThat(first.tags()).containsExactly("attendance", "punch");
        assertThat(first.problems()).isEmpty();
    }

    @Test
    void VYB0666_AC2_theSameFileAlwaysProducesTheSameRows() throws Exception {
        // The point of not using a model here: extraction is repeatable, so re-uploading
        // a corrected sheet changes exactly the rows the author changed.
        assertThat(parser.parse(realTemplate())).isEqualTo(parser.parse(realTemplate()));
    }

    @Test
    void VYB0666_AC1_mapsEveryColumnByItsHeaderNotItsPosition() {
        PrdRow r = parser.parse(sheet(row(HEADER), row(filled()))).get(0);

        assertThat(r.statement()).isEqualTo("The system shall capture punch events.");
        assertThat(r.owner()).isEqualTo("R. Chen");
        assertThat(r.requestedBy()).isEqualTo("HR Operations Head");
        assertThat(r.targetRelease()).isEqualTo("3.2");
        assertThat(r.verificationMethod()).isEqualTo("Test");
        assertThat(r.notes()).isEqualTo("Scope note");
        assertThat(r.dependsOnRefs()).containsExactly("HR-ATT-00", "HR-ATT-09");
        assertThat(r.sourceRow()).isEqualTo(2);
    }

    @Test
    void VYB0666_AC1_reorderedColumnsStillLandInTheRightFields() {
        // Position-based reading would put the statement in the title here.
        PrdRow r = parser.parse(sheet(
            row("Requirement Statement *", "Requirement Title *", "Product *", "App *", "Capability *", "Type *", "Priority *"),
            row("The statement.", "The title", "P", "A", "C", "Functional", "High"))).get(0);

        assertThat(r.title()).isEqualTo("The title");
        assertThat(r.statement()).isEqualTo("The statement.");
    }

    @Test
    void VYB0666_AC1_stripsTheHelpTextAndMandatoryMarkerFromHeaderLabels() {
        PrdRow r = parser.parse(sheet(
            row("Product *\nPick from the portfolio list", "App *", "Capability *",
                "Requirement Title *\nA short imperative phrase", "Requirement Statement *", "Type *", "Priority *"),
            row("Valam", "HRI", "Attendance", "T", "S", "Functional", "Low"))).get(0);

        assertThat(r.product()).isEqualTo("Valam");
        assertThat(r.title()).isEqualTo("T");
    }

    @Test
    void VYB0666_AC1_splitsAcceptanceCriteriaOneToALineAndDropsTheAuthorsNumbering() {
        PrdRow r = parser.parse(sheet(row(HEADER), row(filled()))).get(0);

        assertThat(r.acceptanceCriteria())
            .containsExactly("Appears within 60s.", "Records channel and device ID.");
    }

    @Test
    void VYB0666_AC1_normalisesVocabularySpellingsToTheRegistersValues() {
        String[] cells = filled();
        cells[8] = "non-functional";
        cells[9] = "  high  ";
        PrdRow r = parser.parse(sheet(row(HEADER), row(cells))).get(0);

        assertThat(r.type()).isEqualTo("NON_FUNCTIONAL");
        assertThat(r.priority()).isEqualTo("HIGH");
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void VYB0666_AC3_reportsAnUnknownTypeRatherThanDefaultingIt() {
        // Defaulting to FUNCTIONAL would put a wrong value in the register that reads as
        // deliberate. The row still comes back so the user sees every problem at once.
        String[] cells = filled();
        cells[8] = "Usability-ish";
        PrdRow r = parser.parse(sheet(row(HEADER), row(cells))).get(0);

        assertThat(r.type()).isNull();
        assertThat(r.isValid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("Usability-ish") && p.contains("FUNCTIONAL"));
    }

    @Test
    void VYB0666_AC3_namesEveryMissingMandatoryCellOnTheRow() {
        String[] cells = filled();
        cells[2] = "";
        cells[9] = "";
        PrdRow r = parser.parse(sheet(row(HEADER), row(cells))).get(0);

        assertThat(r.problems()).hasSize(2)
            .anyMatch(p -> p.startsWith("Product")).anyMatch(p -> p.startsWith("Priority"));
    }

    @Test
    void VYB0666_AC3_reportsTheRowNumberTheUserSeesInTheSpreadsheet() {
        List<PrdRow> rows = parser.parse(sheet(row(HEADER), row(filled()), row(filled())));

        assertThat(rows).extracting(PrdRow::sourceRow).containsExactly(2, 3);
    }

    @Test
    void VYB0666_AC3_skipsBlankAndFormattingOnlyRowsInsteadOfReportingThem() {
        String[] blank = new String[20];
        java.util.Arrays.fill(blank, "");
        String[] strayNoteOnly = blank.clone();
        strayNoteOnly[19] = "remember to fill this in";
        List<PrdRow> rows = parser.parse(sheet(row(HEADER), row(filled()), row(blank), row(strayNoteOnly)));

        assertThat(rows).hasSize(1);
    }

    @Test
    void VYB0666_AC3_refusesTheWholeFileWhenAMandatoryColumnIsAbsent() {
        assertThatThrownBy(() -> parser.parse(sheet(
                row("Requirement Title *", "Requirement Statement *", "Product *"),
                row("T", "S", "P"))))
            .isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining("app")
            .hasMessageContaining("capability");
    }

    @Test
    void VYB0666_AC3_refusesAFileWithNoRecognisableHeader() {
        assertThatThrownBy(() -> parser.parse(sheet(row("Name", "Address"), row("a", "b"))))
            .isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining("standard PRD template");
    }

    @Test
    void VYB0666_AC3_refusesATemplateWithNoRowsFilledIn() {
        String[] blank = new String[20];
        java.util.Arrays.fill(blank, "");
        assertThatThrownBy(() -> parser.parse(sheet(row(HEADER), row(blank))))
            .isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining("nothing to import");
    }

    @Test
    void VYB0666_AC3_refusesProseUploadedAsATemplateByNamingWhatTheHeaderMustContain() {
        // Prose is a legitimate CSV candidate now that .csv is supported, so it gets as
        // far as header detection and is refused there. That is the more useful of the
        // two complaints anyway: the reader is told what the file needs to contain
        // rather than that its container format was wrong.
        assertThatThrownBy(() -> parser.parse("plain text".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining("standard PRD template");
    }
}
