package com.vyoog.attachments;

/** VYB-0901: an upload the policy refuses. {@link Reason} decides the HTTP status the API maps it to. */
public class AttachmentRejectedException extends RuntimeException {

    public enum Reason { TOO_LARGE, TYPE_NOT_ALLOWED, BAD_FILENAME, EMPTY }

    private final Reason reason;

    public AttachmentRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
