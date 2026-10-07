package com.vyoog.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** VYB-0930: the Markdown and Word release notes, built from the same notes the screen shows. */
class ReleaseNotesExporterTest {

    private final ReleaseNotesExporter exporter = new ReleaseNotesExporter();
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private static ReleaseService.NoteItem item(String key, String title, String capability) {
        return new ReleaseService.NoteItem("id-" + key, key, title, capability);
    }

    private static ReleaseService.ReleaseNotes notes() {
        Map<String, List<ReleaseService.NoteItem>> approved = new LinkedHashMap<>();
        approved.put("Billing", List.of(item("VY-1", "Invoice export", "Billing"), item("VY-2", "Tax lines", "Billing")));
        approved.put("Login", List.of(item("VY-3", "Lock after 5 tries", "Login")));
        return new ReleaseService.ReleaseNotes(approved, List.of(item("VY-9", "Audit trail", "Compliance")));
    }

    private static ReleaseNotesExporter.Header header(Instant target) {
        return new ReleaseNotesExporter.Header("Release 4", ReleaseState.FROZEN, target, NOW);
    }

    // ---------------------------------------------------------------- markdown

    @Test
    void VYB0930_AC1_theMarkdownGroupsApprovedItemsByCapabilityAndListsHeldOnesSeparately() {
        String md = exporter.markdown(header(Instant.parse("2027-01-15T00:00:00Z")), notes());

        assertThat(md).startsWith("# Release 4 release notes\n");
        assertThat(md).contains("- State: Frozen", "- Target 15 January 2027", "- Generated 7 October 2026 (3 approved, 1 held)");
        assertThat(md).contains("## Approved", "### Billing", "- VY-1 — Invoice export", "### Login");
        assertThat(md.indexOf("### Billing")).isLessThan(md.indexOf("### Login"));
        assertThat(md).contains("## Held — not approved", "- VY-9 — Audit trail (Compliance)");
        assertThat(md.indexOf("## Approved")).isLessThan(md.indexOf("## Held"));
        assertThat(md).endsWith("\n").doesNotEndWith("\n\n");
    }

    @Test
    void VYB0930_AC1_aReleaseWithNoDateSaysSoAndNothingCommittedIsSaidNotLeftBlank() {
        assertThat(exporter.markdown(header(null), notes())).contains("- No target date");
        String empty = exporter.markdown(header(null), new ReleaseService.ReleaseNotes(Map.of(), List.of()));
        assertThat(empty).contains("Nothing is committed to this release.").doesNotContain("## Approved");
    }

    @Test
    void VYB0930_AC1_heldItemsAreStillListedWhenNothingIsApprovedYet() {
        String md = exporter.markdown(header(null), new ReleaseService.ReleaseNotes(Map.of(), List.of(item("VY-9", "Audit trail", "Compliance"))));
        assertThat(md).contains("No committed requirement has been approved yet.", "## Held — not approved", "VY-9");
    }

    @Test
    void VYB0930_AC2_markdownSyntaxInATitleIsEscapedSoItCannotInjectFormattingOrLinksOrHtml() {
        var hostile = new ReleaseService.ReleaseNotes(Map.of("Cap <b>x</b>", List.of(
            item("VY-5", "[click](http://evil.example) *bold* `code` <script>alert(1)</script> # not a heading", "Cap"))), List.of());
        String md = exporter.markdown(header(null), hostile);

        assertThat(md).doesNotContain("<script>").doesNotContain("<b>");
        assertThat(md).contains("\\[click\\]\\(http://evil.example\\)", "\\*bold\\*", "\\`code\\`", "\\<script\\>", "\\# not a heading");
    }

    @Test
    void VYB0930_AC2_aLineBreakInATitleDoesNotSplitTheListItemInTwo() {
        var multi = new ReleaseService.ReleaseNotes(Map.of("Cap", List.of(item("VY-6", "first line\n## injected heading\r\nthird", "Cap"))), List.of());
        String md = exporter.markdown(header(null), multi);
        assertThat(md.lines().filter(l -> l.startsWith("## injected"))).isEmpty();
        assertThat(md).contains("VY-6 — first line \\#\\# injected heading third");
    }

    // -------------------------------------------------------------------- docx

    private record Docx(Map<String, byte[]> parts, String documentText, Document document) {}

    private static Docx read(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) parts.put(e.getName(), zip.readAllBytes());
        }
        // every part must be well-formed XML, parsed with external entities and DTDs off
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = null;
        for (var e : parts.entrySet()) {
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue()));
            if (e.getKey().equals("word/document.xml")) doc = d;
        }
        StringBuilder text = new StringBuilder();
        var ts = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
        for (int i = 0; i < ts.getLength(); i++) text.append(ts.item(i).getTextContent()).append('\n');
        return new Docx(parts, text.toString(), doc);
    }

    @Test
    void VYB0930_AC3_theWordFileIsAZipOfWellFormedPartsWithTheRequiredEntriesFirst() throws Exception {
        byte[] bytes = exporter.docx(header(Instant.parse("2027-01-15T00:00:00Z")), notes());
        assertThat(bytes[0]).isEqualTo((byte) 'P'); // a zip
        assertThat(bytes[1]).isEqualTo((byte) 'K');
        Docx docx = read(bytes);
        assertThat(docx.parts().keySet()).containsExactly("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml",
            "word/_rels/document.xml.rels");
        assertThat(new String(docx.parts().get("[Content_Types].xml"), StandardCharsets.UTF_8))
            .contains("wordprocessingml.document.main+xml");
    }

    @Test
    void VYB0930_AC3_theWordFileCarriesTheSameContentIncludingTheHeldSection() throws Exception {
        Docx docx = read(exporter.docx(header(Instant.parse("2027-01-15T00:00:00Z")), notes()));
        assertThat(docx.documentText()).contains("Release 4 release notes", "State: Frozen", "Target 15 January 2027",
            "Generated 7 October 2026 (3 approved, 1 held)", "Approved", "Billing", "VY-1 — Invoice export", "VY-3 — Lock after 5 tries",
            "Held — not approved", "VY-9 — Audit trail (Compliance)");
        assertThat(docx.documentText().indexOf("Held — not approved")).isGreaterThan(docx.documentText().indexOf("VY-3"));
        String styles = new String(docx.parts().get("word/styles.xml"), StandardCharsets.UTF_8);
        String document = new String(docx.parts().get("word/document.xml"), StandardCharsets.UTF_8);
        assertThat(document).contains("w:pStyle w:val=\"Title\"", "w:pStyle w:val=\"Heading1\"", "w:pStyle w:val=\"Heading2\"");
        assertThat(styles).contains("w:styleId=\"Heading1\"", "w:styleId=\"Heading2\"", "w:styleId=\"Title\"", "w:styleId=\"ListParagraph\"");
    }

    @Test
    void VYB0930_AC4_xmlSpecialCharactersAreEscapedAndCharactersXmlCannotCarryAreDroppedSoTheFileStaysValid() throws Exception {
        var hostile = new ReleaseService.ReleaseNotes(Map.of("A & B", List.of(
            item("VY-7", "<w:t>&amp;</w:t> \"quoted\" 'single' \u0000nul\u0008bs\u000bvt￾end", "A & B"))), List.of());
        Docx docx = read(exporter.docx(header(null), hostile)); // parsing succeeding is the main assertion

        assertThat(docx.documentText()).contains("A & B", "VY-7 — <w:t>&amp;</w:t> \"quoted\" 'single' nulbsvtend");
        String raw = new String(docx.parts().get("word/document.xml"), StandardCharsets.UTF_8);
        assertThat(raw).contains("&lt;w:t&gt;&amp;amp;&lt;/w:t&gt;").doesNotContain("\u0000").doesNotContain("\u0008");
    }

    @Test
    void VYB0930_AC4_aSupplementaryCharacterSurvivesAndALoneSurrogateIsDropped() throws Exception {
        var notes = new ReleaseService.ReleaseNotes(Map.of("Cap", List.of(item("VY-8", "rocket 🚀 and lone \uD83D end", "Cap"))), List.of());
        Docx docx = read(exporter.docx(header(null), notes));
        assertThat(docx.documentText()).contains("rocket 🚀 and lone  end");
    }

    @Test
    void VYB0930_AC5_theSameNotesGiveTheSameBytes() {
        assertThat(exporter.docx(header(null), notes())).isEqualTo(exporter.docx(header(null), notes()));
        assertThat(exporter.markdown(header(null), notes())).isEqualTo(exporter.markdown(header(null), notes()));
    }

    @Test
    void VYB0930_AC5_anEmptyReleaseStillMakesAValidWordFileThatSaysSo() throws Exception {
        Docx docx = read(exporter.docx(header(null), new ReleaseService.ReleaseNotes(Map.of(), List.of())));
        assertThat(docx.documentText()).contains("Nothing is committed to this release.").doesNotContain("Held");
    }

    @Test
    void VYB0930_AC6_escapingHelpersDoNotChangeOrdinaryText() throws IOException {
        assertThat(ReleaseNotesExporter.md("Plain title 123")).isEqualTo("Plain title 123");
        assertThat(ReleaseNotesExporter.md(null)).isEmpty();
        assertThat(ReleaseNotesExporter.xml("Plain title 123")).isEqualTo("Plain title 123");
        assertThat(ReleaseNotesExporter.xml(null)).isEmpty();
    }
}
