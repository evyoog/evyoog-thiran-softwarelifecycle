package com.vyoog.importqueue;

import java.util.List;

/** VYB-0631/0632/0638: one implementation per {@link UploadKind}. */
public interface DocumentParser {

    UploadKind kind();

    /** @throws DocumentValidationException VYB-0631 AC1: names which rule failed. */
    List<ExtractedCandidate> parse(String rawText);
}
