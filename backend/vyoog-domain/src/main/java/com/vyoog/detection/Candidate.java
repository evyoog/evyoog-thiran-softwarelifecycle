package com.vyoog.detection;

import java.util.UUID;

/**
 * What a {@link Detector} emits — never a {@link Finding} directly. A detector has no
 * idea what already exists in the findings table; {@link FindingReconciler} is the
 * only thing that reads or writes it (VYB-0150 AC2: a detector cannot write outside
 * the findings tables — in practice here, a detector cannot write at all).
 *
 * @param objectRevision the revision of the underlying object this candidate is about,
 *     at the moment it was raised. Drives VYB-0154 ("dismissed until the object's
 *     revision changes") — see {@link FindingReconciler}.
 * @param confidence VYB-0615: only an AI detector sets this (0–1); {@code null} for
 *     the rule/graph detectors that have nothing to be confident about — a finding
 *     either is or isn't a missing acceptance criterion. {@link FindingReconciler}
 *     suppresses a candidate below its rule's configured threshold entirely (AC1) —
 *     never shows it, even flagged weak.
 * @param model VYB-0616: "model + prompt version" as one string (e.g.
 *     {@code "local-hashing-v1@prompt-v1"}) — one column on {@code finding}
 *     (V001__baseline.sql) was already written with exactly that combined shape in
 *     mind, so this doesn't introduce a second one.
 */
public record Candidate(String ruleKey, String objectType, UUID objectId, String discriminator,
                         int objectRevision, String severity, String title, String detail, String suggestion,
                         Double confidence, String model) {

    /** The eight non-AI detectors built in Phases 1–2 — no confidence, no model, unchanged call sites. */
    public Candidate(String ruleKey, String objectType, UUID objectId, String discriminator,
                      int objectRevision, String severity, String title, String detail, String suggestion) {
        this(ruleKey, objectType, objectId, discriminator, objectRevision, severity, title, detail, suggestion,
            null, null);
    }

    /** The deterministic identity a rerun must reproduce exactly (VYB-0151). */
    public String fingerprint() {
        return String.join("|", ruleKey, objectType, objectId.toString(), discriminator == null ? "" : discriminator);
    }
}
