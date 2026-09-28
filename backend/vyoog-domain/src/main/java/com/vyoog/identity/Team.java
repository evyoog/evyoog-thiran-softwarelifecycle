package com.vyoog.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0464: a real team, replacing the "distinct owners" proxy the impact-volume
 * signal previously stood in with (see BUILD-REGISTER.md session 10's disclosure of
 * that gap). Deliberately minimal — a name and a member list (V008's {@code
 * team_member}) — nothing here models a team hierarchy or a team's own capabilities.
 */
@Entity
@Table(name = "team")
public class Team {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Team() {}

    public Team(String name) {
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
}
