package com.vyoog.requirements;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Allocates the next requirement key atomically (VYB-0111). Backed by a Postgres
 * sequence: {@code nextval} is atomic and safe under concurrent creation without any
 * application-level locking, and a gap left by a rolled-back transaction is fine —
 * VYB-0111 only forbids *reusing* a retired key, not gaps.
 */
@Component
public class RequirementKeyAllocator {

    private final JdbcTemplate jdbc;

    public RequirementKeyAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next() {
        String prefix = jdbc.queryForObject(
            "SELECT req_key_prefix FROM app_config WHERE id = 1", String.class);
        Long n = jdbc.queryForObject("SELECT nextval('requirement_key_seq')", Long.class);
        return prefix + "-" + n;
    }
}
