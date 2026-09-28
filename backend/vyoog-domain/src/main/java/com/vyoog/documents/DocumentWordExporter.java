package com.vyoog.documents;

import com.vyoog.requirements.Requirement;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

/**
 * VYB-0212: a real {@code .docx} via Apache POI (already cached locally, no new
 * dependency needed against pom.xml's own POI stack — see BUILD-REGISTER.md's Phase 4
 * disclosure of it being available but unused) — genuine WordprocessingML, not a
 * renamed HTML/RTF file. Grouped by capability (VYB-0211 AC1's numbering rule, reused
 * here rather than only in the prose view).
 */
@Component
public class DocumentWordExporter {

    public byte[] export(Document document, List<Requirement> requirements, Map<java.util.UUID, String> capabilityNames) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            title(doc, document.getTitle());

            Map<String, List<Requirement>> byCapability = requirements.stream()
                .collect(Collectors.groupingBy(
                    r -> r.getCapabilityId() == null ? "Unplaced" : capabilityNames.getOrDefault(r.getCapabilityId(), "Unplaced"),
                    java.util.LinkedHashMap::new, Collectors.toList()));

            int section = 1;
            for (var entry : byCapability.entrySet()) {
                heading(doc, section + ". " + entry.getKey());
                int item = 1;
                for (Requirement r : entry.getValue()) {
                    paragraph(doc, "%d.%d %s (%s)".formatted(section, item, r.getTitle(), r.getKey()), true);
                    paragraph(doc, r.getStatement(), false);
                    item++;
                }
                section++;
            }

            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the Word export", e);
        }
    }

    private void title(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(20);
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(14);
    }

    private void paragraph(XWPFDocument doc, String text, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(bold);
    }
}
