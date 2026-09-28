package com.vyoog.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A local mirror of a Keycloak subject. Keycloak remains the source of truth for
 * identity; this row exists so requirements can reference an owner by foreign key.
 *
 * <p>There is deliberately no password field, and there never will be.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    /** Keycloak 'sub'. */
    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String source = "SSO";

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "mfa_enrolled", nullable = false)
    private boolean mfaEnrolled;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** VYB-0706 AC1: who takes this user's derived tasks while they're on leave. */
    @Column(name = "delegate_id")
    private UUID delegateId;

    /** VYB-0334/0792: who an ageing clarification escalates to when the requirement's capability has no owner. */
    @Column(name = "manager_id")
    private UUID managerId;

    /** VYB-0705 AC1: when {@link #status} last changed — not just what it is now. */
    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public AppUser(String subject, String email, String displayName) {
        this.subject = subject;
        this.email = email;
        this.displayName = displayName;
    }

    private static final java.util.Set<String> VALID_STATUSES =
        java.util.Set.of("ACTIVE", "LEAVE", "DEPARTED", "EXTERNAL");

    public UUID getId() { return id; }
    public String getSubject() { return subject; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSource() { return source; }

    /** VYB-0700: an administrator typed this row in; no identity provider supplied it. */
    void markLocallyCreated() { this.source = "LOCAL"; }

    /**
     * VYB-0700: the real Keycloak subject arrives, and the row stops being a placeholder
     * an administrator created and starts being the identity mirror it was always meant
     * to become. Package-private and one-directional on purpose — a subject that is
     * already real must never be reassigned, which is what would make an email collision
     * into an account takeover. {@link UserProvisioningService} enforces that; this only
     * performs the write.
     */
    void claimSubject(String realSubject) {
        this.subject = realSubject;
        this.source = "SSO";
    }

    public String getStatus() { return status; }

    /** VYB-0705/0706: the only way {@code status} moves — always stamps when it did. */
    public void setStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unknown status: " + status);
        }
        if (!status.equals(this.status)) {
            this.status = status;
            this.statusChangedAt = Instant.now();
        }
    }

    public Instant getStatusChangedAt() { return statusChangedAt; }
    public boolean isMfaEnrolled() { return mfaEnrolled; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void touch() { this.lastSeenAt = Instant.now(); }
    public UUID getDelegateId() { return delegateId; }

    /** VYB-0706 AC1: null clears the delegation. */
    public void setDelegateId(UUID delegateId) { this.delegateId = delegateId; }

    public UUID getManagerId() { return managerId; }
    /** VYB-0792: null clears it — same shape as {@link #setDelegateId}. */
    public void setManagerId(UUID managerId) { this.managerId = managerId; }
}
