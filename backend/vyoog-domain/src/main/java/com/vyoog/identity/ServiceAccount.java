package com.vyoog.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A registered CI/integration principal (VYB-0305/0311/0710). {@code clientId} is the
 * Keycloak client id a client-credentials token's {@code azp} claim carries — that's
 * the join key {@link ServiceAccountRepository#existsByClientId} uses to recognise a
 * caller as a service account rather than a person.
 *
 * <p>VYB-0711: {@code scopes} defaults empty, meaning an account with none can do
 * nothing — enforced at {@link ServiceAccountService#hasScope}'s call sites, not here;
 * this class only carries the data. Mapped as a native Postgres {@code text[]} (V001's
 * own column type) via Hibernate 6's array support, rather than a join table — a
 * service account's scope list is small, unordered, and never queried by individual
 * element from SQL, which is exactly the case that type exists for.
 */
@Entity
@Table(name = "service_account")
public class ServiceAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    private String purpose;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> scopes = new ArrayList<>();

    @Column(name = "key_issued_at", nullable = false)
    private Instant keyIssuedAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "previous_client_id")
    private String previousClientId;

    @Column(name = "previous_key_issued_at")
    private Instant previousKeyIssuedAt;

    /** VYB-0712 AC1: the previous key/clientId keeps working until this instant. */
    @Column(name = "key_rotation_overlap_until")
    private Instant keyRotationOverlapUntil;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    protected ServiceAccount() {}

    public ServiceAccount(String name, String purpose, String clientId, List<String> scopes) {
        this.name = name;
        this.purpose = purpose;
        this.clientId = clientId;
        this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
    }

    public void touch() { this.lastUsedAt = Instant.now(); }

    /**
     * VYB-0712: a new client id replaces the current one; the old one is remembered
     * (as "previous") and stays valid until {@code now + overlapDays} (AC1) rather
     * than being invalidated the instant this returns — real key rotation is a
     * cutover, not an instant swap, precisely so nothing already using the old key
     * mid-flight breaks immediately.
     */
    public void rotate(String newClientId, int overlapDays) {
        this.previousClientId = this.clientId;
        this.previousKeyIssuedAt = this.keyIssuedAt;
        this.keyRotationOverlapUntil = Instant.now().plus(overlapDays, java.time.temporal.ChronoUnit.DAYS);
        this.clientId = newClientId;
        this.keyIssuedAt = Instant.now();
        this.rotatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getPurpose() { return purpose; }
    public String getClientId() { return clientId; }
    public List<String> getScopes() { return scopes; }
    public Instant getKeyIssuedAt() { return keyIssuedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public String getPreviousClientId() { return previousClientId; }
    public Instant getKeyRotationOverlapUntil() { return keyRotationOverlapUntil; }
    public Instant getRotatedAt() { return rotatedAt; }

    /** VYB-0710 AC2: never used vs. used long ago are visibly different states, not both "no recent activity." */
    public boolean everUsed() { return lastUsedAt != null; }

    /** VYB-0712 AC1: still within the overlap window after a rotation. */
    public boolean previousKeyStillValid(String candidateClientId) {
        return previousClientId != null && previousClientId.equals(candidateClientId)
            && keyRotationOverlapUntil != null && keyRotationOverlapUntil.isAfter(Instant.now());
    }
}
