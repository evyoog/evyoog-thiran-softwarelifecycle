package com.vyoog.importqueue;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * VYB-0631: the loosely-validated kind — anything splits into candidates, nothing is
 * rejected. Splits on blank lines and strips a leading bullet/number marker if one is
 * there, but doesn't require one (that's exactly the difference from {@link
 * StandardSpecDocumentParser}).
 */
@Component
public class FreeformDocumentParser implements DocumentParser {

    @Override
    public UploadKind kind() {
        return UploadKind.FREEFORM;
    }

    @Override
    public List<ExtractedCandidate> parse(String rawText) {
        List<ExtractedCandidate> out = new ArrayList<>();
        if (rawText == null) return out;
        String[] paragraphs = rawText.split("\\r?\\n\\s*\\r?\\n");
        int index = 0;
        for (String raw : paragraphs) {
            String text = raw.strip().replaceAll("\\s+", " ");
            text = text.replaceFirst("^[-*•]\\s+", "").replaceFirst("^\\d+[.)]\\s+", "");
            if (text.isBlank()) continue;
            index++;
            out.add(new ExtractedCandidate("p" + index, text, "paragraph " + index));
        }
        return out;
    }
}
