package com.vyoog.detection;

/** Mirrors the CHECK constraint on {@code finding.state} in V001__baseline.sql. */
public enum FindingState {
    OPEN, ACCEPTED, DISMISSED, RESOLVED
}
