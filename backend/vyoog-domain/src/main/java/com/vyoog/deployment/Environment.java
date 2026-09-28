package com.vyoog.deployment;

import jakarta.persistence.*;
import java.util.UUID;

/** VYB-0480: ordered explicitly (AC1) — {@code ordinal}, not alphabetical, decides the sequence a build passes through. */
@Entity
@Table(name = "environment")
public class Environment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private short ordinal;

    protected Environment() {}

    public Environment(String name, short ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public short getOrdinal() { return ordinal; }
}
