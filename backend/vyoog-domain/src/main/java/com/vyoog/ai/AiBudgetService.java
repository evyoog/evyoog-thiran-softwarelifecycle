package com.vyoog.ai;

import com.vyoog.platform.audit.AuditService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0939 (F29): a cap on the tokens the AI may use, per UTC day and per UTC calendar month. A cap is a count of tokens and
 * never money: nothing here converts tokens to a price (CLAUDE.md rule 7, D32). Either may be unset (no cap); where both are
 * set, the tighter one decides.
 *
 * <p>When the cap is reached, further calls are refused with a reason in words ({@link AiProviderUnavailableException}, which
 * every caller already copes with). The used figure is read from the ledger and held for a few seconds, plus whatever this
 * instance has recorded since, so a call already in flight can finish and usage can overshoot a little; another instance's
 * calls are seen within the hold. Calls whose tokens the provider did not report count as zero.
 */
@Service
public class AiBudgetService {

    static final Duration HOLD = Duration.ofSeconds(10);

    public record Limits(Long daily, Long monthly) {}

    private record Snapshot(LocalDate day, int monthKey, Limits limits, long today, long month, Instant loadedAt) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private volatile Snapshot snapshot;

    @org.springframework.beans.factory.annotation.Autowired
    public AiBudgetService(JdbcTemplate jdbc, AuditService audit) {
        this(jdbc, audit, Clock.systemUTC());
    }

    AiBudgetService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static int monthKey(LocalDate d) {
        return d.getYear() * 100 + d.getMonthValue();
    }

    private synchronized Snapshot current() {
        LocalDate day = today();
        Snapshot s = snapshot;
        if (s != null && s.day().equals(day) && s.monthKey() == monthKey(day) && clock.instant().isBefore(s.loadedAt().plus(HOLD))) return s;
        Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant monthStart = day.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Object> used = jdbc.queryForMap("""
            SELECT COALESCE(SUM(total_tokens) FILTER (WHERE called_at >= ?), 0) AS today, COALESCE(SUM(total_tokens), 0) AS month
            FROM ai_call WHERE called_at >= ?
            """, Timestamp.from(dayStart), Timestamp.from(monthStart));
        Limits limits = jdbc.queryForObject("SELECT ai_token_budget_daily, ai_token_budget_monthly FROM app_config WHERE id = 1",
            (rs, n) -> new Limits(rs.getObject(1, Long.class), rs.getObject(2, Long.class)));
        snapshot = new Snapshot(day, monthKey(day), limits, ((Number) used.get("today")).longValue(), ((Number) used.get("month")).longValue(), clock.instant());
        return snapshot;
    }

    public Limits limits() {
        return current().limits();
    }

    /** Tokens used so far today and this month (UTC). */
    public record Used(long today, long month) {}

    public Used used() {
        Snapshot s = current();
        return new Used(s.today(), s.month());
    }

    /** @throws AiProviderUnavailableException a cap is reached, naming which and when it resets */
    public void requireWithinBudget() {
        Snapshot s = current();
        Limits l = s.limits();
        if (l.daily() != null && s.today() >= l.daily()) {
            throw new AiProviderUnavailableException(
                "The AI token budget for today is used up (%s of %s tokens). It resets at 00:00 UTC, or an administrator can raise it in Administration, Settings."
                    .formatted(fmt(s.today()), fmt(l.daily())));
        }
        if (l.monthly() != null && s.month() >= l.monthly()) {
            throw new AiProviderUnavailableException(
                "The AI token budget for this month is used up (%s of %s tokens). It resets on %s 00:00 UTC, or an administrator can raise it in Administration, Settings."
                    .formatted(fmt(s.month()), fmt(l.monthly()), s.day().withDayOfMonth(1).plusMonths(1)));
        }
    }

    /** Adds tokens just recorded to the figures held, so this instance sees its own calls at once. */
    public synchronized void recordUsed(long tokens) {
        Snapshot s = snapshot;
        if (tokens <= 0 || s == null || !s.day().equals(today()) || s.monthKey() != monthKey(today())) return;
        snapshot = new Snapshot(s.day(), s.monthKey(), s.limits(), s.today() + tokens, s.month() + tokens, s.loadedAt());
    }

    /** Forget what is held; the next check reads the ledger and the settings again. */
    public synchronized void invalidate() {
        snapshot = null;
    }

    /**
     * Sets the caps; null clears one. A cap must be a positive number of tokens. One audit event records before and after.
     */
    public Limits setLimits(Long daily, Long monthly, UUID actor) {
        if (daily != null && daily <= 0) throw new IllegalArgumentException("The daily token budget must be more than zero, or empty for no limit.");
        if (monthly != null && monthly <= 0) throw new IllegalArgumentException("The monthly token budget must be more than zero, or empty for no limit.");
        Limits before = limits();
        jdbc.update("UPDATE app_config SET ai_token_budget_daily = ?, ai_token_budget_monthly = ? WHERE id = 1", daily, monthly);
        invalidate();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("daily", before.daily());
        b.put("monthly", before.monthly());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("daily", daily);
        a.put("monthly", monthly);
        audit.record(actor, "settings.ai-token-budget-changed", "APP_CONFIG", null, b, a);
        return new Limits(daily, monthly);
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
