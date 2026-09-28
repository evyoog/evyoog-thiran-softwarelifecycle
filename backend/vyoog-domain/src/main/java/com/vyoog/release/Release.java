package com.vyoog.release;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "release")
public class Release {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReleaseState state = ReleaseState.PLANNED;

    /** VYB-0372 (V009): when this release is targeted to ship — nullable, nothing invents one. */
    @Column(name = "target_date")
    private Instant targetDate;

    protected Release() {}

    public Release(String name) { this.name = name; }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public ReleaseState getState() { return state; }
    public void setState(ReleaseState state) { this.state = state; }
    public Instant getTargetDate() { return targetDate; }
    public void setTargetDate(Instant targetDate) { this.targetDate = targetDate; }
}
