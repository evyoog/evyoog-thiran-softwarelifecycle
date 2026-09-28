package com.vyoog.importqueue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * VYB-0638: a real (if partial) ReqIF reader — no reqif4j-style library is added for
 * this, since {@code javax.xml}'s own DOM parser already covers the common case: for
 * each {@code SPEC-OBJECT}, its {@code IDENTIFIER} attribute becomes the candidate's
 * tag (AC1 — identifiers preserved), every descendant {@code ATTRIBUTE-VALUE-STRING}'s
 * {@code THE-VALUE} is concatenated as its text, and every {@code SPEC-RELATION}'s
 * source/target becomes a {@link ExtractedCandidate#relatedTags()} entry (AC2 — links
 * become trace links, resolved once every tag in the batch has a committed
 * requirement id). Attribute *names* (which datatype/attribute-definition a value
 * belongs to) aren't resolved — every string value on a spec object is treated as
 * part of its text, which is a simplification a full ReqIF importer wouldn't make.
 */
@Component
public class ReqIfDocumentParser implements DocumentParser {

    @Override
    public UploadKind kind() {
        return UploadKind.REQIF;
    }

    @Override
    public List<ExtractedCandidate> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new DocumentValidationException("empty-file", "The uploaded file has no content.");
        }
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening: this document came from an upload, not a trusted source.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(new InputSource(new StringReader(rawText)));
        } catch (Exception e) {
            throw new DocumentValidationException("not-well-formed-xml",
                "This isn't well-formed XML: " + e.getMessage());
        }

        NodeList specObjects = doc.getElementsByTagName("SPEC-OBJECT");
        if (specObjects.getLength() == 0) {
            throw new DocumentValidationException("no-spec-objects",
                "No <SPEC-OBJECT> elements were found — this doesn't look like a ReqIF export.");
        }

        List<ExtractedCandidate> out = new ArrayList<>();
        for (int i = 0; i < specObjects.getLength(); i++) {
            Element specObject = (Element) specObjects.item(i);
            String identifier = specObject.getAttribute("IDENTIFIER");
            if (identifier.isBlank()) continue;

            StringBuilder text = new StringBuilder();
            NodeList values = specObject.getElementsByTagName("ATTRIBUTE-VALUE-STRING");
            for (int v = 0; v < values.getLength(); v++) {
                Element value = (Element) values.item(v);
                String theValue = value.getAttribute("THE-VALUE");
                if (!theValue.isBlank()) {
                    if (text.length() > 0) text.append(" — ");
                    text.append(theValue);
                }
            }
            if (text.length() == 0) continue; // a spec object with no string content isn't a candidate requirement

            out.add(new ExtractedCandidate(identifier, text.toString(), "SPEC-OBJECT " + identifier,
                relatedIdentifiers(doc, identifier)));
        }
        return out;
    }

    /** VYB-0638 AC2: every SPEC-RELATION naming this identifier as its source, by the target it points to. */
    private List<String> relatedIdentifiers(Document doc, String sourceIdentifier) {
        List<String> related = new ArrayList<>();
        NodeList relations = doc.getElementsByTagName("SPEC-RELATION");
        for (int i = 0; i < relations.getLength(); i++) {
            Element relation = (Element) relations.item(i);
            String source = refIn(relation, "SOURCE");
            String target = refIn(relation, "TARGET");
            if (sourceIdentifier.equals(source) && target != null) {
                related.add(target);
            }
        }
        return related;
    }

    /** ReqIF nests the actual reference as {@code <SOURCE><SPEC-OBJECT-REF>ID</SPEC-OBJECT-REF></SOURCE>}. */
    private String refIn(Element relation, String childTag) {
        NodeList children = relation.getElementsByTagName(childTag);
        if (children.getLength() == 0) return null;
        Node refNode = ((Element) children.item(0)).getElementsByTagName("SPEC-OBJECT-REF").item(0);
        return refNode == null ? null : refNode.getTextContent().strip();
    }
}
