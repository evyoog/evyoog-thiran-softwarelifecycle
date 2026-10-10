package com.vyoog.importqueue;

import java.util.Optional;

/**
 * VYB-0940: the finished steps of one AI extraction, kept so a failed one can be continued rather than started again. A step is
 * a model call whose reply has been read (one chunk of the document, the summary, the check, a batch of briefs). The pipeline
 * asks for a step by key before making its call and saves it after.
 *
 * <p>{@link #NONE} remembers nothing: every step is made, as before. That is what a re-analysis of a stored document uses.
 */
public interface ExtractionSteps {

    <T> Optional<T> load(String key, Class<T> type);

    /** Saves what the step produced, in its own transaction, so it survives a later failure. */
    <T> void save(String key, T value);

    ExtractionSteps NONE = new ExtractionSteps() {
        @Override public <T> Optional<T> load(String key, Class<T> type) {
            return Optional.empty();
        }

        @Override public <T> void save(String key, T value) {
            // nothing is remembered
        }
    };
}
