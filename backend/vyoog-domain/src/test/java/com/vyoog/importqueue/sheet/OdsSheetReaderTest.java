package com.vyoog.importqueue.sheet;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The OpenDocument reader, against packages built here rather than a checked-in fixture —
 * every case below is one the format encodes in a way that silently corrupts a grid if
 * mishandled, and building the XML makes exactly which construct is under test visible.
 */
class OdsSheetReaderTest {

    private final OdsSheetReader reader = new OdsSheetReader();

    /** Wraps sheet XML in the minimum OpenDocument package the reader needs. */
    private static byte[] ods(String tableXml) {
        String content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:spreadsheet>
                <table:table table:name="Sheet1">%s</table:table>
              </office:spreadsheet></office:body>
            </office:document-content>
            """.formatted(tableXml);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder("<table:table-row>");
        for (String c : cells) sb.append(c);
        return sb.append("</table:table-row>").toString();
    }

    private static String cell(String text) {
        return "<table:table-cell><text:p>" + text + "</text:p></table:table-cell>";
    }

    @Test
    void VYB0666_AC1_readsAPlainGrid() {
        List<List<String>> rows = reader.read(ods(
            row(cell("Title"), cell("Statement")) + row(cell("Capture punch"), cell("The system shall..."))));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("Title", "Statement");
        assertThat(rows.get(1)).containsExactly("Capture punch", "The system shall...");
    }

    @Test
    void VYB0666_AC1_expandsRepeatedColumnsSoLaterCellsKeepTheirPosition() {
        // OpenDocument stores consecutive identical cells once with a count. Ignoring it
        // collapses the row, and every column after the gap lands under the wrong header.
        List<List<String>> rows = reader.read(ods(row(
            cell("A"),
            "<table:table-cell table:number-columns-repeated=\"3\"/>",
            cell("E"))));

        assertThat(rows.get(0)).containsExactly("A", "", "", "", "E");
    }

    @Test
    void VYB0666_AC1_keepsOneLinePerParagraphSoAcceptanceCriteriaSurvive() {
        // The template's Acceptance Criteria column is "one criterion per line", and each
        // line is its own <text:p>. Concatenating them would produce one run-on criterion.
        List<List<String>> rows = reader.read(ods(row(
            "<table:table-cell><text:p>1. Punch appears within 60s.</text:p>"
                + "<text:p>2. Channel and device id are recorded.</text:p></table:table-cell>")));

        assertThat(rows.get(0).get(0))
            .isEqualTo("1. Punch appears within 60s.\n2. Channel and device id are recorded.");
    }

    @Test
    void VYB0666_AC1_ignoresCellCommentsSoAHeaderReadsAsItsColumnName() {
        // The PRD template puts each column's help text in a cell comment, and a comment
        // is built from the same <text:p> elements as the value and stored inside the
        // cell. Read alike, every annotated header came back as its own help text.
        List<List<String>> rows = reader.read(ods(row(
            "<table:table-cell>"
                + "<office:annotation><text:p>Pick from the list, or type a new one.</text:p></office:annotation>"
                + "<text:p>Capability *</text:p></table:table-cell>")));

        assertThat(rows.get(0)).containsExactly("Capability *");
    }

    @Test
    void VYB0666_AC1_treatsTheSpaceElementAsASpace() {
        // <text:s/> is how the format stores a run of spaces; without it words run together.
        List<List<String>> rows = reader.read(ods(row(
            "<table:table-cell><text:p>Columns marked<text:s/>* are mandatory</text:p></table:table-cell>")));

        assertThat(rows.get(0).get(0)).isEqualTo("Columns marked * are mandatory");
    }

    @Test
    void VYB0666_AC1_dropsTheMillionRowPaddingSpreadsheetsWriteAtTheEnd() {
        // LibreOffice fills the grid with a repeated empty row. Expanding it literally
        // would exhaust the heap on a 20 KB file.
        List<List<String>> rows = reader.read(ods(
            row(cell("Only row")) + "<table:table-row table:number-rows-repeated=\"1048576\"/>"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("Only row");
    }

    @Test
    void VYB0666_AC1_readsOnlyTheFirstSheet() {
        // A second sheet in this template is a lookup list, not more requirements.
        String two = "<table:table table:name=\"Sheet1\">" + row(cell("real")) + "</table:table>"
                   + "<table:table table:name=\"Lists\">" + row(cell("lookup")) + "</table:table>";
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(("""
                <?xml version="1.0"?>
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                  <office:body><office:spreadsheet>%s</office:spreadsheet></office:body>
                </office:document-content>
                """.formatted(two)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(reader.read(out.toByteArray())).hasSize(1);
    }

    @Test
    void VYB0666_AC1_skipsLeadingValidationTablesAndReadsTheFirstGrid() {
        // Some ODS writers serialize dropdown/list ranges as one-column tables named
        // val1, val2, etc. before the visible sheet. Treating the first table as the
        // sheet makes the PRD parser look for headers in a lookup list.
        String tables = "<table:table table:name=\"val1\">" + row(cell("Functional")) + row(cell("Security")) + "</table:table>"
                      + "<table:table table:name=\"Sheet1\">" + row(cell("Product *"), cell("App *"), cell("Capability *"))
                      + row(cell("Valam"), cell("HRI"), cell("Attendance")) + "</table:table>";
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(("""
                <?xml version="1.0"?>
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                  <office:body><office:spreadsheet>%s</office:spreadsheet></office:body>
                </office:document-content>
                """.formatted(tables)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(reader.read(out.toByteArray()))
            .containsExactly(
                List.of("Product *", "App *", "Capability *"),
                List.of("Valam", "HRI", "Attendance"));
    }

    @Test
    void VYB0666_AC1_recognisesOnlyOpenDocumentAndLeavesXlsxToTheOtherReader() {
        byte[] odsBytes = ods(row(cell("x")));
        assertThat(reader.supports(odsBytes)).isTrue();
        assertThat(new XlsxSheetReader().supports(odsBytes)).isFalse();
        assertThat(reader.supports("not a zip at all".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void VYB0666_AC1_aZipThatIsNotOpenDocumentIsRefusedByName() {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("something-else.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> reader.read(out.toByteArray()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no content.xml");
    }
}
