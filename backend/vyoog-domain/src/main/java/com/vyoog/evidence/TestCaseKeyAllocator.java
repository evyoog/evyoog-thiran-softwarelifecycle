package com.vyoog.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0363: a draft test case is raised by a person through Vyoog itself, unlike a
 * CI-ingested one whose key already belongs to whatever CI system owns it — same
 * reasoning as {@code DefectKeyAllocator}. Allocated from {@code test_case_key_seq}
 * (V009), atomically, gaps-on-rollback allowed.
 */
@Component
public class TestCaseKeyAllocator {

    private final JdbcTemplate jdbc;

    public TestCaseKeyAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next() {
        Long n = jdbc.queryForObject("SELECT nextval('test_case_key_seq')", Long.class);
        return "TC-" + n;
    }
}
