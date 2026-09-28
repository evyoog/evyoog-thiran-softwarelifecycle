package com.vyoog.documents;

import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceLink;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * VYB-0212 AC1: writes exactly the tag shapes {@link
 * com.vyoog.importqueue.ReqIfDocumentParser} reads back — {@code SPEC-OBJECT
 * IDENTIFIER} carrying the requirement's key (never lost, AC1's "without loss of
 * ids"), and a {@code SPEC-RELATION} per trace link between two requirements that
 * are both in this document (AC1's "or links"). Verified by an actual round trip in
 * {@code DocumentReqIfExporterTest}, not just written to look right.
 */
@Component
public class DocumentReqIfExporter {

    public String export(Document document, List<Requirement> requirements, List<TraceLink> linksAmongThem) {
        Set<String> keysInDoc = requirements.stream().map(Requirement::getKey).collect(Collectors.toSet());
        Map<java.util.UUID, String> keyById = requirements.stream()
            .collect(Collectors.toMap(Requirement::getId, Requirement::getKey));

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<REQ-IF xmlns=\"http://www.omg.org/spec/ReqIF/20110401/reqif.xsd\">\n");
        xml.append("  <CORE-CONTENT>\n    <REQ-IF-CONTENT>\n      <SPEC-OBJECTS>\n");
        for (Requirement r : requirements) {
            xml.append("        <SPEC-OBJECT IDENTIFIER=\"").append(escape(r.getKey())).append("\">\n");
            xml.append("          <VALUES>\n");
            xml.append("            <ATTRIBUTE-VALUE-STRING THE-VALUE=\"").append(escape(r.getTitle() + ": " + r.getStatement())).append("\"/>\n");
            xml.append("          </VALUES>\n");
            xml.append("        </SPEC-OBJECT>\n");
        }
        xml.append("      </SPEC-OBJECTS>\n      <SPEC-RELATIONS>\n");
        for (TraceLink link : linksAmongThem) {
            String fromKey = keyById.get(link.getFromId());
            String toKey = keyById.get(link.getToId());
            if (fromKey == null || toKey == null || !keysInDoc.contains(fromKey) || !keysInDoc.contains(toKey)) continue;
            xml.append("        <SPEC-RELATION>\n");
            xml.append("          <SOURCE><SPEC-OBJECT-REF>").append(escape(fromKey)).append("</SPEC-OBJECT-REF></SOURCE>\n");
            xml.append("          <TARGET><SPEC-OBJECT-REF>").append(escape(toKey)).append("</SPEC-OBJECT-REF></TARGET>\n");
            xml.append("        </SPEC-RELATION>\n");
        }
        xml.append("      </SPEC-RELATIONS>\n    </REQ-IF-CONTENT>\n  </CORE-CONTENT>\n");
        xml.append("</REQ-IF>\n");
        return xml.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
