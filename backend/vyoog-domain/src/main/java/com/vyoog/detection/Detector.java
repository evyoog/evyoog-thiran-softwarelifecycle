package com.vyoog.detection;

import java.util.List;
import java.util.UUID;

/**
 * VYB-0150: a pure function from current state to candidate findings. No detector
 * implementation may inject a repository that writes anything — code review, not the
 * compiler, is what enforces that today; see BUILD-REGISTER.md for why an ArchUnit
 * rule for it was deferred rather than half-built.
 *
 * <p>AC1 ("running a detector twice produces identical candidates") falls out for
 * free as long as a detector only reads and does no time-/randomness-based branching
 * — every detector here is a single SQL query with no such branching.
 */
public interface Detector {

    /** Must match a key already seeded in {@code gap_rule_template} (V001__baseline.sql). */
    String ruleKey();

    List<Candidate> scan();

    /**
     * VYB-0161 AC2: a bounded re-evaluation of one object, for the triggered-on-change
     * path — a real {@code WHERE id = ?}, not {@link #scan()} filtered in Java (that
     * would still be a full table scan under the hood, which is exactly what "bounded"
     * rules out). Detectors override this; there's no generic way to derive a bounded
     * query from an unbounded one.
     */
    List<Candidate> scanOne(UUID objectId);

    /**
     * VYB-0940: true if scanning calls a model provider (an embedding, an adjudication). Such a detector is never run inside
     * the caller's database transaction: {@link DetectionSweepService#rescanObject} runs it after the commit instead.
     */
    default boolean callsModel() {
        return false;
    }
}
