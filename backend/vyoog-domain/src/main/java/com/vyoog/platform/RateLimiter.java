package com.vyoog.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private final Map<String, Instant> lastCall = new ConcurrentHashMap<>();

    /**
     * @return null if the call may proceed (and is recorded as having just happened);
     *     otherwise the duration the caller must still wait.
     */
    public synchronized Duration checkAndRecord(String key, Duration cooldown) {
        Instant now = Instant.now();
        Instant previous = lastCall.get(key);
        if (previous != null) {
            Duration elapsed = Duration.between(previous, now);
            if (elapsed.compareTo(cooldown) < 0) {
                return cooldown.minus(elapsed);
            }
        }
        lastCall.put(key, now);
        return null;
    }

    public void requireNotLimited(String key, Duration cooldown) {
        Duration remaining = checkAndRecord(key, cooldown);
        if (remaining != null) {
            throw new RateLimitExceededException(remaining);
        }
    }
}
