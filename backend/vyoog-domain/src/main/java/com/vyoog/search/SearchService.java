package com.vyoog.search;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0766: one field, four kinds of thing, grouped (AC1). AC2 ("results respect the
 * user's grants") is the same disclosed gap as everywhere else in this codebase —
 * none of the four underlying tables are scope-filtered by caller today (see
 * RoleCapabilityRegistry); this returns exactly what those four tables' own
 * unfiltered list endpoints already would.
 */
@Service
public class SearchService {

    private static final int LIMIT_PER_KIND = 8;

    private final JdbcTemplate jdbc;

    public SearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(String kind, UUID id, String label, String detail) {}

    public List<Result> search(String q) {
        if (q == null || q.isBlank()) return List.of();
        String pattern = "%" + q.trim() + "%";
        List<Result> out = new java.util.ArrayList<>();

        out.addAll(jdbc.query("""
            SELECT id, key, title FROM requirement
            WHERE deleted_at IS NULL AND (title ILIKE ? OR key ILIKE ? OR statement ILIKE ?)
            ORDER BY key LIMIT ?
            """,
            (rs, n) -> new Result("REQUIREMENT", UUID.fromString(rs.getString("id")),
                rs.getString("key") + " " + rs.getString("title"), null),
            pattern, pattern, pattern, LIMIT_PER_KIND));

        out.addAll(jdbc.query("""
            SELECT id, name, code FROM capability WHERE archived_at IS NULL AND name ILIKE ?
            ORDER BY name LIMIT ?
            """,
            (rs, n) -> new Result("CAPABILITY", UUID.fromString(rs.getString("id")),
                rs.getString("name"), rs.getString("code")),
            pattern, LIMIT_PER_KIND));

        out.addAll(jdbc.query("""
            SELECT id, term, definition FROM glossary_term WHERE term ILIKE ? ORDER BY term LIMIT ?
            """,
            (rs, n) -> new Result("GLOSSARY_TERM", UUID.fromString(rs.getString("id")),
                rs.getString("term"), rs.getString("definition")),
            pattern, LIMIT_PER_KIND));

        out.addAll(jdbc.query("""
            SELECT id, title, rule_key FROM finding WHERE state = 'OPEN' AND title ILIKE ?
            ORDER BY first_seen_at DESC LIMIT ?
            """,
            (rs, n) -> new Result("FINDING", UUID.fromString(rs.getString("id")),
                rs.getString("title"), rs.getString("rule_key")),
            pattern, LIMIT_PER_KIND));

        return out;
    }
}
