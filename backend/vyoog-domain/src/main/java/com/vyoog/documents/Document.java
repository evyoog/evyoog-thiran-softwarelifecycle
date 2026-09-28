package com.vyoog.documents;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * VYB-0210: a curated, ordered subset of a product's requirements — {@code
 * document_requirement} (V008) carries the membership and ordering; this row is just
 * the header. {@code revision} bumps on every membership change (add/remove/reorder),
 * distinct from any one requirement's own revision.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    @NotBlank
    @Column(nullable = false)
    private String title;

    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private int revision = 1;

    @Column(nullable = false)
    private String state = "DRAFT";

    protected Document() {}

    public Document(String key, String title, UUID productId) {
        this.key = key;
        this.title = title;
        this.productId = productId;
    }

    public void bumpRevision() { this.revision++; }
    public void setState(String state) { this.state = state; }
    public void setTitle(String title) { this.title = title; }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public UUID getProductId() { return productId; }
    public int getRevision() { return revision; }
    public String getState() { return state; }
}
