package com.vyoog.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A role scoped to the platform or to one product/application/capability/release
 * (VYB-0343 needs the scoped lookup; separation-of-duties and service-account checks
 * elsewhere in Phase 2 need "does this grant currently apply"). Revocation is soft —
 * {@code revokedAt} — and expiry is a plain timestamp comparison, both read at query
 * time rather than a stored "active" flag that could drift from either.
 */
@Entity
@Table(name = "access_grant")
public class AccessGrant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private ScopeType scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AccessGrant() {}

    public AccessGrant(UUID userId, AccessRole role, ScopeType scopeType, UUID scopeId,
                        UUID grantedBy, Instant expiresAt) {
        if (scopeType == ScopeType.PLATFORM && scopeId != null) {
            throw new IllegalArgumentException("a PLATFORM grant has no scope id");
        }
        if (scopeType != ScopeType.PLATFORM && scopeId == null) {
            throw new IllegalArgumentException("a %s grant needs a scope id".formatted(scopeType));
        }
        this.userId = userId;
        this.role = role;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.grantedBy = grantedBy;
        this.expiresAt = expiresAt;
    }

    public void revoke() { this.revokedAt = Instant.now(); }

    public boolean isActive() {
        Instant now = Instant.now();
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public AccessRole getRole() { return role; }
    public ScopeType getScopeType() { return scopeType; }
    public UUID getScopeId() { return scopeId; }
    public UUID getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
