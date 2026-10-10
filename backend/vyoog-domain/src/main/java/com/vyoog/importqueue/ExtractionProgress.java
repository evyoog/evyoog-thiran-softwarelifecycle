package com.vyoog.importqueue;

import java.util.Optional;
import java.util.UUID;

/**
 * VYB-0940: where an AI extraction of one batch stands, and the steps it has finished.
 *
 * <p>One extraction of a batch runs at a time: {@link #claim} marks the batch EXTRACTING and refuses a second request. A
 * claim older than {@link JdbcExtractionProgress#STALE_AFTER} is taken over, because the instance that held it is gone. When an
 * extraction fails, {@link #fail} records the reason and the batch is EXTRACTION_FAILED with its finished steps kept; extracting
 * again continues from them.
 */
public interface ExtractionProgress {

    /**
     * @return the state the batch was in before (so a failure can put it back), or empty if another extraction of it is running
     */
    Optional<String> claim(UUID batchId);

    /** The finished steps of this batch, for text with this digest; steps made from other text are dropped first. */
    ExtractionSteps steps(UUID batchId, String sourceDigest);

    /** Records why it stopped. A batch that was UPLOADED or EXTRACTION_FAILED becomes EXTRACTION_FAILED; any other is put back. */
    void fail(UUID batchId, String reason, String previousState);

    /**
     * The extraction is done: forgets the finished steps and marks the batch EXTRACTED. Called inside the transaction that saves the
     * candidates, so they all land together or not at all.
     */
    void complete(UUID batchId);

    /** Remembers nothing and never refuses: for code that builds an {@link ImportService} without a database. */
    ExtractionProgress NONE = new ExtractionProgress() {
        @Override public Optional<String> claim(UUID batchId) {
            return Optional.of("UPLOADED");
        }

        @Override public ExtractionSteps steps(UUID batchId, String sourceDigest) {
            return ExtractionSteps.NONE;
        }

        @Override public void fail(UUID batchId, String reason, String previousState) {
            // nothing is recorded
        }

        @Override public void complete(UUID batchId) {
            // nothing to forget
        }
    };
}
