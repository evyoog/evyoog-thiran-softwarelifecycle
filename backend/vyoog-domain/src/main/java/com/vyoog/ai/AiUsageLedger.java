package com.vyoog.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0939: writes one row per model call to {@code ai_call}. The row goes in its own transaction, so a call that was made is on
 * record even when the caller's transaction rolls back (the tokens were spent either way). A failure to write it is logged and
 * counted, never thrown: losing a ledger row must not fail an AI call the person is waiting on.
 *
 * <p>A budget refusal is recorded at most once a minute per purpose, so a sweep that is refused a thousand times leaves a
 * handful of rows, not a thousand.
 */
@Service
public class AiUsageLedger {

    private static final Logger log = LoggerFactory.getLogger(AiUsageLedger.class);
    static final long REFUSAL_ROW_EVERY_MILLIS = 60_000;

    public enum Outcome { OK, FAILED, BUDGET_REFUSED }

    public record Entry(String purpose, String promptVersion, String endpoint, String model, CallKind kind, Outcome outcome,
                        Integer promptTokens, Integer completionTokens, long durationMillis) {
        /** The tokens the provider reported, or null if it reported none. Only a successful call has any. */
        Integer totalTokens() {
            if (outcome != Outcome.OK || (promptTokens == null && completionTokens == null)) return null;
            return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate independently;
    private final ConcurrentHashMap<String, AtomicLong> lastRefusalRow = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier millis;

    @org.springframework.beans.factory.annotation.Autowired
    public AiUsageLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, System::currentTimeMillis);
    }

    AiUsageLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions, java.util.function.LongSupplier millis) {
        this.jdbc = jdbc;
        this.independently = new TransactionTemplate(transactions);
        this.independently.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.millis = millis;
    }

    /** @return true if a row was written */
    public boolean record(Entry e) {
        if (e.outcome() == Outcome.BUDGET_REFUSED) {
            AtomicLong last = lastRefusalRow.computeIfAbsent(e.purpose(), k -> new AtomicLong(0));
            long now = millis.getAsLong();
            long before = last.get();
            if (now - before < REFUSAL_ROW_EVERY_MILLIS || !last.compareAndSet(before, now)) return false;
        }
        try {
            independently.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO ai_call (purpose, prompt_version, endpoint, model, call_kind, outcome, prompt_tokens, completion_tokens, total_tokens, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, e.purpose(), e.promptVersion(), e.endpoint(), e.model(), e.kind().name(), e.outcome().name(),
                e.promptTokens(), e.completionTokens(), e.totalTokens(), (int) Math.min(e.durationMillis(), Integer.MAX_VALUE)));
            return true;
        } catch (RuntimeException ex) {
            log.error("[ai] could not write the usage ledger row for {} ({}): {}", e.purpose(), e.outcome(), ex.toString());
            return false;
        }
    }
}
