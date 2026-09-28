package com.vyoog.attachments;

import jakarta.persistence.*;
import java.util.UUID;

/** VYB-0123: one filename, many versions — see {@link AttachmentVersion}. */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "current_version", nullable = false)
    private short currentVersion = 1;

    protected Attachment() {}

    public Attachment(UUID requirementId, String filename) {
        this.requirementId = requirementId;
        this.filename = filename;
    }

    void bumpVersion() { this.currentVersion += 1; }

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public String getFilename() { return filename; }
    public short getCurrentVersion() { return currentVersion; }
}
