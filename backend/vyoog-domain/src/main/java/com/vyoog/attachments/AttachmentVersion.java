package com.vyoog.attachments;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One immutable version of an attachment's bytes (VYB-0123: never overwritten). */
@Entity
@Table(name = "attachment_version")
public class AttachmentVersion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "attachment_id", nullable = false)
    private UUID attachmentId;

    @Column(nullable = false)
    private short version;

    /** The object store key this version's bytes live at — never reused. */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    protected AttachmentVersion() {}

    public AttachmentVersion(UUID attachmentId, short version, String storageKey, String contentType,
                              long sizeBytes, UUID uploadedBy) {
        this.attachmentId = attachmentId;
        this.version = version;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
    }

    public UUID getId() { return id; }
    public UUID getAttachmentId() { return attachmentId; }
    public short getVersion() { return version; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
