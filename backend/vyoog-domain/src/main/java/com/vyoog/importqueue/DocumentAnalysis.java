package com.vyoog.importqueue;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * VYB-0667: one run of the document analysis pipeline over an uploaded batch, and the
 * description it proposed.
 *
 * <p>It is a proposal and stays one (Principle 7 / VYB-0619): {@code state} starts at
 * PROPOSED and only a person moves it to ACCEPTED or DISMISSED. Nothing here is ever
 * copied into a requirement, a candidate or a document record by the analysis itself.
 *
 * <p>Runs are kept rather than overwritten. A re-analysis after the model or the prompt
 * changed is a different answer to the same question, and the earlier one is what a
 * reviewer's earlier decision was made against.
 */
@Entity
@Table(name = "import_document_analysis")
public class DocumentAnalysis {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private String state = "PROPOSED";

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    /** The findings the description was built from, each with its verbatim evidence and location. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String findings;

    /** Short phrases naming what the document is about, as judged by the synthesis agent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String themes;

    /**
     * Claims the critic could still not tie to a finding after the one revision pass.
     * Empty is the good case; non-empty is shown to the reviewer rather than hidden,
     * because an unsupported claim is the thing worth looking at.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unsupported_claims", columnDefinition = "jsonb")
    private String unsupportedClaims;

    @Column(name = "chunks_total", nullable = false)
    private int chunksTotal;

    /** Below {@link #chunksTotal} when the per-run AI budget ran out — never silently equal. */
    @Column(name = "chunks_analysed", nullable = false)
    private int chunksAnalysed;

    @Column(name = "findings_kept", nullable = false)
    private int findingsKept;

    /** Findings the model returned that failed the verbatim-evidence check and were dropped. */
    @Column(name = "findings_rejected", nullable = false)
    private int findingsRejected;

    @Column(name = "noise_blocks_discarded", nullable = false)
    private int noiseBlocksDiscarded;

    @Column(name = "revision_ran", nullable = false)
    private boolean revisionRan;

    /** VYB-0616: model provenance on every AI output. */
    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "ai_calls", nullable = false)
    private int aiCalls;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    /** VYB-0619: a dismissal carries its reason, the same way detector dismissals do. */
    @Column(name = "dismiss_reason")
    private String dismissReason;

    protected DocumentAnalysis() {}

    public DocumentAnalysis(UUID batchId, String description, String findings, String themes,
                            String unsupportedClaims, int chunksTotal, int chunksAnalysed,
                            int findingsKept, int findingsRejected, int noiseBlocksDiscarded,
                            boolean revisionRan, String model, String promptVersion, int aiCalls) {
        this.batchId = batchId;
        this.description = description;
        this.findings = findings;
        this.themes = themes;
        this.unsupportedClaims = unsupportedClaims;
        this.chunksTotal = chunksTotal;
        this.chunksAnalysed = chunksAnalysed;
        this.findingsKept = findingsKept;
        this.findingsRejected = findingsRejected;
        this.noiseBlocksDiscarded = noiseBlocksDiscarded;
        this.revisionRan = revisionRan;
        this.model = model;
        this.promptVersion = promptVersion;
        this.aiCalls = aiCalls;
    }

    public void accept(UUID actor) {
        this.state = "ACCEPTED";
        this.decidedBy = actor;
        this.decidedAt = Instant.now();
    }

    public void dismiss(UUID actor, String reason) {
        this.state = "DISMISSED";
        this.decidedBy = actor;
        this.decidedAt = Instant.now();
        this.dismissReason = reason;
    }

    /** True when the AI budget stopped the run before every chunk had been read. */
    public boolean isPartial() {
        return chunksAnalysed < chunksTotal;
    }

    public UUID getId() { return id; }
    public UUID getBatchId() { return batchId; }
    public String getState() { return state; }
    public String getDescription() { return description; }
    public String getFindings() { return findings; }
    public String getThemes() { return themes; }
    public String getUnsupportedClaims() { return unsupportedClaims; }
    public int getChunksTotal() { return chunksTotal; }
    public int getChunksAnalysed() { return chunksAnalysed; }
    public int getFindingsKept() { return findingsKept; }
    public int getFindingsRejected() { return findingsRejected; }
    public int getNoiseBlocksDiscarded() { return noiseBlocksDiscarded; }
    public boolean isRevisionRan() { return revisionRan; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public int getAiCalls() { return aiCalls; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDismissReason() { return dismissReason; }
}