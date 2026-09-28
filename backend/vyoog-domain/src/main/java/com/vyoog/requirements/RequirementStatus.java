package com.vyoog.requirements;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle is a gated state machine, not a free-text field. Illegal transitions
 * are rejected by the service layer with a reason.
 *
 * <p>VYB-0813 (D17): this is a full replacement of the prior five-state machine
 * (DRAFT/IN_REVIEW/REVIEWED/APPROVED/REJECTED, D15/D16) — not an extension of it.
 * NEEDS_REVISION is new: a decision maker can send a REVIEWED requirement back to its
 * author instead of approving or rejecting it, and a REJECTED requirement reopens into
 * NEEDS_REVISION rather than back to DRAFT. APPROVED is now fully terminal — no
 * transition leaves it; editing an approved requirement is refused exactly as before
 * (via {@code RequirementService#update}), and a fork-to-new-version mechanism for
 * that case is deliberately deferred, not built here.
 */
public enum RequirementStatus {
    DRAFT,
    IN_REVIEW,
    REVIEWED,
    NEEDS_REVISION,
    APPROVED,
    REJECTED;

    public Set<RequirementStatus> allowedNext() {
        return switch (this) {
            case DRAFT          -> EnumSet.of(IN_REVIEW);
            // Withdraw (back to the author) or hand off review complete. Rejecting
            // directly from IN_REVIEW is deliberately not offered any more — a
            // decision is only ever made once a review is actually complete.
            case IN_REVIEW      -> EnumSet.of(DRAFT, REVIEWED);
            // The decision maker's three outcomes for a completed review.
            case REVIEWED       -> EnumSet.of(APPROVED, REJECTED, NEEDS_REVISION);
            // Resubmit skips DRAFT entirely — the author already has a decision to
            // act on, not a blank slate.
            case NEEDS_REVISION -> EnumSet.of(IN_REVIEW);
            // Terminal: baselined. No transition leaves it from here.
            case APPROVED       -> EnumSet.noneOf(RequirementStatus.class);
            // Reopens into NEEDS_REVISION, not DRAFT — a rejection already carries a
            // reason for the author to act on, which DRAFT has no field for.
            case REJECTED       -> EnumSet.of(NEEDS_REVISION);
        };
    }

    public boolean canMoveTo(RequirementStatus target) {
        return allowedNext().contains(target);
    }
}
