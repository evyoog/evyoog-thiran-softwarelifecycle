package com.vyoog.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0937: the display names of the people in this system, compiled once into the pattern {@link Redactor} uses to find
 * them. Held for a minute rather than read on every model call; a person added in the last minute is not yet matched.
 * Every status counts: someone who has left is still personal data.
 */
@Component
public class KnownPeople {

    static final Duration HOLD_FOR = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Redactor redactor;
    private final Clock clock;

    private volatile Instant loadedAt;
    private volatile Pattern pattern;

    @Autowired
    public KnownPeople(JdbcTemplate jdbc, Redactor redactor) {
        this(jdbc, redactor, Clock.systemUTC());
    }

    KnownPeople(JdbcTemplate jdbc, Redactor redactor, Clock clock) {
        this.jdbc = jdbc;
        this.redactor = redactor;
        this.clock = clock;
    }

    /** Drops the held list; the next call reloads it. A person added a moment ago is matched from then on. */
    public synchronized void forget() {
        loadedAt = null;
        pattern = null;
    }

    /** The compiled names, or null if there are none worth matching. */
    public Pattern names() {
        Instant now = clock.instant();
        Instant at = loadedAt;
        if (at == null || now.isAfter(at.plus(HOLD_FOR))) {
            synchronized (this) {
                if (loadedAt == null || now.isAfter(loadedAt.plus(HOLD_FOR))) {
                    List<String> names = jdbc.queryForList("SELECT display_name FROM app_user WHERE display_name IS NOT NULL", String.class);
                    pattern = redactor.nameMatcher(names);
                    loadedAt = now;
                }
            }
        }
        return pattern;
    }
}
