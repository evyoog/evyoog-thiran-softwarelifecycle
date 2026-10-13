package com.vyoog.importqueue.sheet;

import com.vyoog.importqueue.DocumentValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * VYB-0957: a ReqIF file (or a {@code .reqifz}, a ZIP holding one) read as a grid, by attribute <b>name</b>. Each
 * {@code SPEC-OBJECT} becomes a row. Its column headings are the long names of its attribute definitions (what the tool that
 * exported it called them: {@code ReqIF.Name}, {@code ReqIF.Text}, {@code Type}, {@code Priority}), so the same rules that read a
 * spreadsheet read it: nothing is guessed, and a file with no attribute called Type or Priority is reported, not defaulted.
 *
 * <ul>
 *   <li>String, XHTML (the {@code ReqIF.Text} of most tools, flattened to plain text with its paragraph breaks), integer, real,
 *       date and boolean values are read as text; an enumeration is the long name of the value(s) chosen.</li>
 *   <li>"Your Ref" is the object's {@code IDENTIFIER}, so identifiers are preserved (VYB-0638).</li>
 *   <li>Every {@code SPEC-RELATION} becomes a "Depends On" entry on its source, naming the target's identifier, resolved into a
 *       trace link when both ends are imported (VYB-0638).</li>
 * </ul>
 *
 * <p>The XML is read with external entities and DTDs refused (the file came from an upload), and a {@code .reqifz} entry is
 * capped in size so a small archive cannot expand without limit.
 */
public final class ReqIfSheetReader implements SheetReader {

    static final String YOUR_REF = "Your Ref";
    static final String DEPENDS_ON = "Depends On";
    private static final long MAX_UNZIPPED = 50L * 1024 * 1024;

    @Override
    public String format() { return "reqif"; }

    @Override
    public boolean hierarchyColumnsRequired() { return false; }

    @Override
    public boolean headerIsFirstRow() { return true; }

    @Override
    public boolean supports(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        if (XlsxSheetReader.isZip(bytes)) return reqifEntryName(bytes) != null;
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        return head.contains("<REQ-IF") || head.contains(":REQ-IF");
    }

    private static String reqifEntryName(byte[] zip) {
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".reqif")) return e.getName();
        } catch (Exception notReadable) {
            return null;
        }
        return null;
    }

    private static byte[] xmlOf(byte[] bytes) {
        if (!XlsxSheetReader.isZip(bytes)) return bytes;
        try (var in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".reqif")) continue;
                var out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNZIPPED) throw new DocumentValidationException("too-large", "The ReqIF inside this archive is larger than 50 MB when unpacked.");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (DocumentValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentValidationException("unreadable-reqifz", "Could not read this .reqifz archive: " + e.getMessage());
        }
        throw new DocumentValidationException("no-reqif-in-archive", "This archive holds no .reqif file.");
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        Document doc = parse(xmlOf(bytes));
        List<Element> objects = elements(doc, "SPEC-OBJECT");
        if (objects.isEmpty()) {
            throw new DocumentValidationException("no-spec-objects", "No SPEC-OBJECT elements were found: this does not look like a ReqIF export.");
        }
        Map<String, String> definitions = new HashMap<>();
        for (Element e : elementsStartingWith(doc, "ATTRIBUTE-DEFINITION-")) {
            if (!e.getLocalName().endsWith("-REF") && !e.getAttribute("IDENTIFIER").isBlank()) definitions.put(e.getAttribute("IDENTIFIER"), name(e));
        }
        Map<String, String> enumValues = new HashMap<>();
        for (Element e : elements(doc, "ENUM-VALUE")) enumValues.put(e.getAttribute("IDENTIFIER"), name(e));
        Map<String, List<String>> relations = relations(doc);

        Map<String, Integer> columns = new LinkedHashMap<>();
        columns.put(YOUR_REF, 0);
        List<Map<String, String>> values = new ArrayList<>();
        for (Element object : objects) {
            String id = object.getAttribute("IDENTIFIER");
            if (id.isBlank()) continue; // an object that cannot be referred to cannot be tracked
            Map<String, String> row = new LinkedHashMap<>();
            row.put(YOUR_REF, id);
            Element container = child(object, "VALUES");
            if (container != null) {
                for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element value) || !value.getLocalName().startsWith("ATTRIBUTE-VALUE-")) continue;
                    String definitionId = definitionRef(value);
                    String heading = definitions.get(definitionId);
                    if (heading == null || heading.isBlank()) continue;
                    String text = valueText(value, enumValues);
                    if (text.isBlank()) continue;
                    columns.computeIfAbsent(heading, h -> columns.size());
                    row.merge(heading, text, (a, b) -> a + "\n" + b);
                }
            }
            if (relations.containsKey(id)) {
                columns.computeIfAbsent(DEPENDS_ON, h -> columns.size());
                row.putIfAbsent(DEPENDS_ON, String.join(", ", relations.get(id)));
            }
            values.add(row);
        }

        List<List<String>> grid = new ArrayList<>();
        grid.add(new ArrayList<>(columns.keySet()));
        for (Map<String, String> row : values) {
            List<String> cells = new ArrayList<>();
            for (String heading : columns.keySet()) cells.add(row.getOrDefault(heading, ""));
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
            grid.add(cells);
        }
        return grid;
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XXE hardening: this came from an upload, not a trusted source.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (InputStream in = new ByteArrayInputStream(xml)) {
                return factory.newDocumentBuilder().parse(in);
            }
        } catch (Exception e) {
            throw new DocumentValidationException("not-well-formed-xml", "This is not well-formed XML: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ small DOM helpers (namespace-agnostic)

    private static List<Element> elements(Document doc, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < all.getLength(); i++) out.add((Element) all.item(i));
        return out;
    }

    private static List<Element> elementsStartingWith(Document doc, String prefix) {
        List<Element> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.getLocalName() != null && e.getLocalName().startsWith(prefix)) out.add(e);
        }
        return out;
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static String name(Element e) {
        String longName = e.getAttribute("LONG-NAME");
        return longName.isBlank() ? e.getAttribute("IDENTIFIER") : longName.strip();
    }

    /** The identifier of the attribute definition a value is for: {@code <DEFINITION><ATTRIBUTE-DEFINITION-…-REF>id</…></DEFINITION>}. */
    private static String definitionRef(Element value) {
        Element definition = child(value, "DEFINITION");
        if (definition == null) return null;
        for (Node n = definition.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element ref) return ref.getTextContent().strip();
        }
        return null;
    }

    private static String valueText(Element value, Map<String, String> enumValues) {
        String kind = value.getLocalName();
        if (kind.equals("ATTRIBUTE-VALUE-ENUMERATION")) {
            Element values = child(value, "VALUES");
            if (values == null) return "";
            List<String> chosen = new ArrayList<>();
            for (Node n = values.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element ref) chosen.add(enumValues.getOrDefault(ref.getTextContent().strip(), ref.getTextContent().strip()));
            }
            return String.join(", ", chosen);
        }
        if (kind.equals("ATTRIBUTE-VALUE-XHTML")) {
            Element the = child(value, "THE-VALUE");
            return the == null ? "" : xhtmlText(the);
        }
        return value.getAttribute("THE-VALUE").strip();
    }

    private static final java.util.Set<String> BREAKS = java.util.Set.of("p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6");

    /** XHTML flattened to plain text, one line per paragraph, list item or break. */
    static String xhtmlText(Element root) {
        StringBuilder out = new StringBuilder();
        collect(root, out);
        List<String> lines = new ArrayList<>();
        for (String line : out.toString().split("\n")) {
            String t = line.replaceAll("\\s+", " ").strip();
            if (!t.isEmpty()) lines.add(t);
        }
        return String.join("\n", lines);
    }

    private static void collect(Node node, StringBuilder out) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                collect(e, out);
                if (e.getLocalName() != null && BREAKS.contains(e.getLocalName().toLowerCase())) out.append('\n');
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                out.append(n.getNodeValue());
            }
        }
    }

    /** Source identifier to the identifiers it points at, in file order. */
    private static Map<String, List<String>> relations(Document doc) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Element relation : elements(doc, "SPEC-RELATION")) {
            String source = refIn(relation, "SOURCE");
            String target = refIn(relation, "TARGET");
            if (source != null && target != null && !source.equals(target)) {
                List<String> targets = out.computeIfAbsent(source, k -> new ArrayList<>());
                if (!targets.contains(target)) targets.add(target);
            }
        }
        return out;
    }

    private static String refIn(Element relation, String side) {
        Element wrapper = child(relation, side);
        if (wrapper == null) return null;
        for (Node n = wrapper.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element ref) return ref.getTextContent().strip();
        }
        return null;
    }
}
