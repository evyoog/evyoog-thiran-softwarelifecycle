package com.vyoog.ai;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0939 (F30): what the AI used, read from the {@code ai_call} ledger. Any signed-in person may see it. It is broken down by
 * day, by purpose, by prompt version and by model; there is no per-person breakdown because the ledger holds no person
 * (CLAUDE.md rule 7, D32), and nothing here is an amount of money — only calls and tokens.
 */
@Service
public class AiUsageReport {

    public static final int MAX_DAYS = 366;

    public record Day(LocalDate day, long calls, long tokens) {}

    /** One row of the month's breakdown: calls that succeeded, failed or were refused for the budget, and the tokens reported. */
    public record Breakdown(String purpose, String promptVersion, String model,
                            long calls, long failed, long refused,
                            long promptTokens, long completionTokens, long totalTokens) {}

    public record Summary(AiBudgetService.Limits limits, long usedToday, long usedThisMonth,
                          LocalDate monthStart, LocalDate monthResets, List<Day> byDay, List<Breakdown> thisMonth) {}

    private final JdbcTemplate jdbc;
    private final AiBudgetService budget;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AiUsageReport(JdbcTemplate jdbc, AiBudgetService budget) {
        this(jdbc, budget, Clock.systemUTC());
    }

    AiUsageReport(JdbcTemplate jdbc, AiBudgetService budget, Clock clock) {
        this.jdbc = jdbc;
        this.budget = budget;
        this.clock = clock;
    }

    /** @param days how many UTC days to chart, ending today (1 to {@value #MAX_DAYS}) */
    public Summary summary(int days) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate first = today.minusDays(days - 1L);
        LocalDate monthStart = today.withDayOfMonth(1);

        Map<LocalDate, long[]> perDay = new HashMap<>();
        jdbc.query("""
            SELECT (called_at AT TIME ZONE 'UTC')::date AS d, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens
            FROM ai_call WHERE called_at >= ? AND outcome = 'OK' GROUP BY 1
            """, rs -> {
                perDay.put(rs.getDate("d").toLocalDate(), new long[] {rs.getLong("calls"), rs.getLong("tokens")});
            }, Timestamp.from(first.atStartOfDay().toInstant(ZoneOffset.UTC)));
        List<Day> byDay = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            long[] v = perDay.getOrDefault(d, new long[] {0, 0});
            byDay.add(new Day(d, v[0], v[1]));
        }

        List<Breakdown> month = jdbc.query("""
            SELECT purpose, prompt_version, model,
                   COUNT(*) FILTER (WHERE outcome = 'OK') AS calls,
                   COUNT(*) FILTER (WHERE outcome = 'FAILED') AS failed,
                   COUNT(*) FILTER (WHERE outcome = 'BUDGET_REFUSED') AS refused,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM ai_call WHERE called_at >= ?
            GROUP BY purpose, prompt_version, model
            ORDER BY total_tokens DESC, purpose, prompt_version, model
            """, (rs, n) -> new Breakdown(rs.getString("purpose"), rs.getString("prompt_version"), rs.getString("model"),
                rs.getLong("calls"), rs.getLong("failed"), rs.getLong("refused"),
                rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("total_tokens")),
            Timestamp.from(monthStart.atStartOfDay().toInstant(ZoneOffset.UTC)));

        budget.invalidate();
        AiBudgetService.Used used = budget.used();
        return new Summary(budget.limits(), used.today(), used.month(), monthStart, monthStart.plusMonths(1), byDay, month);
    }
}
