package com.vyoog.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0315/0316. A commit has no natural UUID — its identity is a SHA — but the trace
 * graph's CODE object type needs one to be a {@code trace_link} endpoint; {@link #id}
 * is that mapping. {@code hasTrailer} is the one fact {@link UntracedCommitDetector}
 * needs that can't be derived by "is there a trace_link from this id" once a commit
 * with no trailer, by definition, never gets one.
 */
@Entity
@Table(name = "ingested_commit")
public class IngestedCommit {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String sha;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "author_email")
    private String authorEmail;

    @Column(name = "has_trailer", nullable = false)
    private boolean hasTrailer;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();

    protected IngestedCommit() {}

    public IngestedCommit(String sha, String message, String authorEmail, boolean hasTrailer) {
        this.sha = sha;
        this.message = message;
        this.authorEmail = authorEmail;
        this.hasTrailer = hasTrailer;
    }

    public UUID getId() { return id; }
    public String getSha() { return sha; }
    public String getMessage() { return message; }
    public String getAuthorEmail() { return authorEmail; }
    public boolean isHasTrailer() { return hasTrailer; }
}
