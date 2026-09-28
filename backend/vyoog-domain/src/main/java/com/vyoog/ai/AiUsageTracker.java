package com.vyoog.ai;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0620: bounds model calls per run. {@code DetectionSweepService} calls {@link
 * #beginRun()} once at the start of a sweep; each AI detector calls {@link
 * #tryConsume()} before an actual model call (an embedding, an adjudication) and
 * defers rather than proceeding once the bound is hit (AC1) — the candidates it
 * couldn't afford to check this run just aren't raised yet, not treated as "checked,
 * found nothing." AC2: usage is queryable at any time, not just at run's end.
 */
@Service
public class AiUsageTracker {

    private final JdbcTemplate jdbc;
    private final AtomicInteger usedThisRun = new AtomicInteger(0);
    private volatile int limitThisRun = Integer.MAX_VALUE;

    public AiUsageTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void beginRun() {
        Integer limit = jdbc.queryForObject("SELECT ai_calls_per_run_limit FROM app_config WHERE id = 1", Integer.class);
        this.limitThisRun = limit == null ? Integer.MAX_VALUE : limit;
        this.usedThisRun.set(0);
    }

    /** @return true if this call is within budget and may proceed; false means defer. */
    public boolean tryConsume() {
        return usedThisRun.incrementAndGet() <= limitThisRun;
    }

    public int used() { return usedThisRun.get(); }
    public int limit() { return limitThisRun; }
}
