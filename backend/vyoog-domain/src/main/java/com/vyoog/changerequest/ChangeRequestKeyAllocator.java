package com.vyoog.changerequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChangeRequestKeyAllocator {

    private final JdbcTemplate jdbc;

    public ChangeRequestKeyAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next() {
        Long n = jdbc.queryForObject("SELECT nextval('change_request_key_seq')", Long.class);
        return "CR-" + n;
    }
}
