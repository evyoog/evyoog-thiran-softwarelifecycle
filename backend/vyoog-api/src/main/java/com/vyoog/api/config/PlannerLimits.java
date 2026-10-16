package com.vyoog.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0921 (D54): the limits on what the Agile Planner may send to {@code POST /api/v1/planner/requirements}. Read on every request (not
 * copied into other beans), so a test can change one and put it back. Set from the environment; each default is documented in
 * {@code docs/09-integrations/agile-planner-requirements-intake.md}.
 */
@Component
public class PlannerLimits {

    /** The largest request body accepted, in bytes, whatever its fields hold. */
    private int maxBodyBytes;
    /** At most this many pushes per service account per minute (a counted limit, so a burst is fine and a flood is not). */
    private int pushesPerMinute;
    /** A token older than this many minutes is refused; 0 turns the check off. */
    private int maxTokenAgeMinutes;

    public PlannerLimits(@Value("${vyoog.planner.max-body-bytes:262144}") int maxBodyBytes,
                         @Value("${vyoog.planner.pushes-per-minute:600}") int pushesPerMinute,
                         @Value("${vyoog.planner.max-token-age-minutes:15}") int maxTokenAgeMinutes) {
        if (maxBodyBytes < 1024 || pushesPerMinute < 1 || maxTokenAgeMinutes < 0) {
            throw new IllegalStateException("vyoog.planner limits must be: max-body-bytes >= 1024, pushes-per-minute >= 1, max-token-age-minutes >= 0");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.pushesPerMinute = pushesPerMinute;
        this.maxTokenAgeMinutes = maxTokenAgeMinutes;
    }

    public int maxBodyBytes() { return maxBodyBytes; }
    public int pushesPerMinute() { return pushesPerMinute; }
    public int maxTokenAgeMinutes() { return maxTokenAgeMinutes; }
}
