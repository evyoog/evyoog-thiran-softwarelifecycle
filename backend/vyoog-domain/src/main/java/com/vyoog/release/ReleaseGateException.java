package com.vyoog.release;

import java.util.List;

/** VYB-0928: a transition refused because readiness gates failed and no override was given. */
public class ReleaseGateException extends IllegalStateException {

    public record Failed(ReleaseGate gate, String detail) {}

    private final transient List<Failed> failed;

    public ReleaseGateException(List<Failed> failed) {
        super("The release is not ready: " + String.join("; ", failed.stream().map(Failed::detail).toList())
            + ". An approver can proceed anyway by giving a reason.");
        this.failed = List.copyOf(failed);
    }

    public List<Failed> failed() {
        return failed;
    }
}
