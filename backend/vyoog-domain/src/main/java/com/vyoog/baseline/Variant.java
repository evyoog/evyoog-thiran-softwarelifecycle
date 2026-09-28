package com.vyoog.baseline;

import jakarta.persistence.*;
import java.util.UUID;

/** VYB-0473: a product edition. */
@Entity
@Table(name = "variant")
public class Variant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    protected Variant() {}

    public Variant(String name) { this.name = name; }

    public UUID getId() { return id; }
    public String getName() { return name; }
}
