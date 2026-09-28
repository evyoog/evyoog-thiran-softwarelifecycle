package com.vyoog.defect;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Same reasoning as {@code RequirementKeyAllocator}: a defect is raised by a person
 * through Vyoog itself (unlike a test case, whose key belongs to whatever CI system
 * owns it), so it needs a key of our own — allocated from {@code defect_key_seq}
 * (V005), atomically, gaps-on-rollback allowed.
 */
@Component
public class DefectKeyAllocator {

    private final JdbcTemplate jdbc;

    public DefectKeyAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next() {
        Long n = jdbc.queryForObject("SELECT nextval('defect_key_seq')", Long.class);
        return "DEF-" + n;
    }
}
