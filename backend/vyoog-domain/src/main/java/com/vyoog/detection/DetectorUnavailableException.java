package com.vyoog.detection;

/**
 * VYB-0603 AC2: thrown by an AI detector when its provider (an embedding service, an
 * LLM adjudicator) isn't reachable or isn't configured — {@link DetectionSweepService}
 * catches this and reports the rule as unavailable this cycle, leaving its existing
 * findings untouched, rather than letting an empty candidate list resolve them all as
 * if the gaps they named had actually gone away.
 */
public class DetectorUnavailableException extends RuntimeException {
    public DetectorUnavailableException(String message) {
        super(message);
    }

    public DetectorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
