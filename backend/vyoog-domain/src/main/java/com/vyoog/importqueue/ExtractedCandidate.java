package com.vyoog.importqueue;

import java.util.List;

/**
 * VYB-0632 AC1: {@code sourceLocation} names where in the document this came from.
 * {@code relatedTags} (VYB-0638 AC2) is whatever the source format's own link
 * mechanism named as related — ReqIF's {@code SPEC-RELATION}, mainly — resolved into
 * real trace links after commit, once every tag in the batch has a requirement id.
 */
public record ExtractedCandidate(String tag, String text, String sourceLocation, List<String> relatedTags) {

    public ExtractedCandidate(String tag, String text, String sourceLocation) {
        this(tag, text, sourceLocation, List.of());
    }
}
