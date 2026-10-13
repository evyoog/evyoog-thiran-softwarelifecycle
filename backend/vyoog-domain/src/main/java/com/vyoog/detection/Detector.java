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

    /**
     * VYB-0960 (D48): true for a detector that compares requirements with one another (the embedding comparisons: possible duplicates
     * and contradictions). Comparing every requirement with every other grows with the square of their number and took hours at 50,000,
     * so the nightly sweep does not call {@link #scan()} for such a detector. It checks only the requirements whose embedding is new
     * or changed since its last pass, each with {@link #scanOne}, exactly as a write does (see {@link IncrementalSweep}).
     */
    default boolean sweepsChangedOnly() {
        return false;
    }

    /**
     * VYB-0960: throws {@link DetectorNotConfiguredException} (or {@link DetectorUnavailableException}) if this detector cannot run at
     * all right now (AI is off), before the sweep reads or moves any position. A detector that can always run does nothing here.
     */
    default void ensureAvailable() {
    }
}
