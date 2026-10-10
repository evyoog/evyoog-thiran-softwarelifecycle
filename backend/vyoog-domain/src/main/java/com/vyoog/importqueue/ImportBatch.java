package com.vyoog.importqueue;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0630: a document accepted for parsing — {@code rawText} (V006) holds exactly
 * what was uploaded, before any candidate extraction. AC1: nothing here ever touches
 * {@code requirement}; only {@code ImportService#commit} does that, and only for the
 * candidates a human selected.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "application_id")
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_kind", nullable = false)
    private UploadKind uploadKind = UploadKind.FREEFORM;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    // VYB-0630 defect fix (2026-08-12): was "PARSED", which no code path here ever
    // meant to set — extractCandidates() only ever writes "EXTRACTED". The frontend's
    // "Extract candidates" button (ImportQueue.tsx) gates on state === 'UPLOADED',
    // so a batch stuck at the old default could never be extracted through the UI.
    // See V014__fix_import_batch_initial_state.sql for the matching column-default
    // and backfill migration.
    @Column(nullable = false)
    private String state = "UPLOADED";

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    /**
     * VYB-0940: why the last AI extraction stopped; null unless the state is EXTRACTION_FAILED. Written by
     * {@link JdbcExtractionProgress} directly, so it is mapped read-only here.
     */
    @Column(name = "extraction_error", columnDefinition = "text", insertable = false, updatable = false)
    private String extractionError;

    protected ImportBatch() {}

    public ImportBatch(String filename, UUID applicationId, UploadKind uploadKind, UUID uploadedBy, String rawText) {
        this.filename = filename;
        this.applicationId = applicationId;
        this.uploadKind = uploadKind;
        this.uploadedBy = uploadedBy;
        this.rawText = rawText;
    }

    public void setState(String state) { this.state = state; }

    public UUID getId() { return id; }
    public String getFilename() { return filename; }
    public UUID getApplicationId() { return applicationId; }
    public UploadKind getUploadKind() { return uploadKind; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getState() { return state; }
    public String getExtractionError() { return extractionError; }
    public String getRawText() { return rawText; }
}
