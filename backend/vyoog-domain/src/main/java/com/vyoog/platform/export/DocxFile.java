package com.vyoog.platform.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A Word (.docx) file written with no document library: a .docx is a zip of a few XML parts, and the exports here need a title,
 * two heading levels, paragraphs and list lines. Every piece of text is escaped for XML, and characters XML cannot carry are
 * removed rather than allowed to corrupt the file. Deterministic: the same content gives the same bytes. Shared by the release notes
 * (VYB-0930) and the evidence pack (VYB-0948).
 */
public final class DocxFile {

    private final StringBuilder body = new StringBuilder();

    public DocxFile title(String text) { return add("Title", text); }
    public DocxFile heading1(String text) { return add("Heading1", text); }
    public DocxFile heading2(String text) { return add("Heading2", text); }
    public DocxFile text(String text) { return add(null, text); }

    private DocxFile add(String style, String text) {
        body.append(paragraph(style, text));
        return this;
    }

    /** A list line: indented with a hanging bullet, no numbering part needed. */
    public DocxFile bullet(String text) {
        body.append(listLine(text));
        return this;
    }

    public byte[] toBytes() {
        String document = XML_HEADER + "<w:document xmlns:w=\"" + W_NS + "\"><w:body>" + body
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" "
            + "w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            // [Content_Types].xml must come first for some readers
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "word/document.xml", document);
            put(zip, "word/styles.xml", STYLES);
            put(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the Word file", e);
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0); // deterministic bytes: the same notes give the same file
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String paragraph(String style, String text) {
        return "<w:p>" + (style == null ? "" : "<w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>") + run(text) + "</w:p>";
    }

    private static String listLine(String text) {
        return "<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:ind w:left=\"567\" w:hanging=\"284\"/></w:pPr>"
            + "<w:r><w:t xml:space=\"preserve\">•</w:t></w:r><w:r><w:tab/></w:r>" + run(text) + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r>";
    }

    /** Escapes for XML text and drops characters XML 1.0 cannot carry (control characters, unpaired surrogates, U+FFFE/FFFF). */
    public static String xml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean ok = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!ok) continue;
            if (cp == '\n' || cp == '\r') { sb.append(' '); continue; }
            switch (cp) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private static final String CONTENT_TYPES = XML_HEADER
        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
        + "</Types>";

    private static final String ROOT_RELS = XML_HEADER
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
        + "</Relationships>";

    private static final String DOCUMENT_RELS = XML_HEADER
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        + "</Relationships>";

    private static final String STYLES = XML_HEADER + "<w:styles xmlns:w=\"" + W_NS + "\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"44\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:spacing w:after=\"60\"/></w:pPr></w:style>"
        + "</w:styles>";
}
