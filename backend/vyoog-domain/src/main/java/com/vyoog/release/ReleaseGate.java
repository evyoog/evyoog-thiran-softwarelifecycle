package com.vyoog.release;

/** VYB-0928: a readiness check on a release's committed scope, configured per guarded transition. */
public enum ReleaseGate {
    /** Something is committed. */
    SCOPE_NOT_EMPTY,
    /** Every committed requirement is Approved (D16: the pipeline's terminal status). */
    ALL_APPROVED,
    /** No open critical gap on a committed requirement. */
    NO_CRITICAL_GAPS,
    /** Nothing committed is unverified, conflicting or unowned (see {@link ReleaseService#blocked}). */
    NO_BLOCKED_ITEMS,
    /** At least {@code threshold} percent of the committed requirements are verified. The only gate with a threshold. */
    VERIFIED_SHARE
}
