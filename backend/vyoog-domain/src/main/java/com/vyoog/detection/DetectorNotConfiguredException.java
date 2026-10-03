package com.vyoog.detection;

/**
 * VYB-0911: a detector cannot run because the feature it needs is switched off or has no
 * credentials (AI is off by default). Unlike a plain {@link DetectorUnavailableException}, which
 * means something that should work did not (a provider outage), this is a steady, expected state, so
 * {@link DetectionSweepService} reports the rule as unavailable exactly the same way but does not
 * log it as a warning on every write.
 */
public class DetectorNotConfiguredException extends DetectorUnavailableException {
    public DetectorNotConfiguredException(String message) {
        super(message);
    }
}
