package com.vyoog.importqueue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * VYB-0631: validated more strictly than freeform — every candidate line must be
 * explicitly numbered ({@code "1.2 The system shall..."}), which is what lets each
 * one preserve a stable, source-given identifier (the number itself) as its tag.
 */
@Component
public class StandardSpecDocumentParser implements DocumentParser {

    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)[.)]?\\s+(.+)$");

    @Override
    public UploadKind kind() {
        return UploadKind.STANDARD_SPEC;
    }

    @Override
    public List<ExtractedCandidate> parse(String rawText) {
        List<ExtractedCandidate> out = new ArrayList<>();
        if (rawText != null) {
            String[] lines = rawText.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = NUMBERED_LINE.matcher(lines[i]);
                if (m.matches()) {
                    out.add(new ExtractedCandidate(m.group(1), m.group(2).strip(), "line " + (i + 1)));
                }
            }
        }
        // VYB-0631 AC1: the one rule this format actually enforces — named explicitly.
        if (out.isEmpty()) {
            throw new DocumentValidationException("numbered-requirement-format",
                "A standard specification document needs at least one numbered requirement line, "
                    + "like \"1.2 The system shall...\" — none were found.");
        }
        return out;
    }
}
