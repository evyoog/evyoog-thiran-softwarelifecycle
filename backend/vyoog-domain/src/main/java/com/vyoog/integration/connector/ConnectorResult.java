package com.vyoog.integration.connector;

import java.util.UUID;

/**
 * VYB-0913: how a {@link ConnectorExecutor#execute} call ended. A failed send is a result, not an
 * exception: the caller decides what a failure means for its own work. (An unconfigured connection
 * is the exception, {@link ConnectorNotConfiguredException}.)
 *
 * @param attempts requests actually sent; 0 when nothing was sent
 * @param httpStatus the last response's status, or null if there was none (a connection failure)
 * @param error a short reason, safe to show: never a header or a secret; null on success
 * @param syncLogId the {@code connector_sync_log} row, or the earlier row for a duplicate
 */
public record ConnectorResult(Outcome outcome, int attempts, Integer httpStatus, String error, UUID syncLogId) {

    public enum Outcome {
        /** The receiver answered 2xx. */
        SUCCEEDED,
        /** Every attempt failed, or the first answer was a definite refusal. */
        FAILED,
        /** The same idempotency key already succeeded; nothing was sent. */
        ALREADY_DONE,
        /** The same idempotency key is being sent right now (another instance or thread); nothing was sent. */
        IN_PROGRESS_ELSEWHERE
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCEEDED || outcome == Outcome.ALREADY_DONE;
    }
}
