package com.vyoog.identity;

import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0700/0705/0706: the user directory as an administrator sees it, status changes,
 * and delegation. Provisioning ({@link UserProvisioningService}) stays separate — that
 * runs on every sign-in and only ever touches the mutable identity-mirror fields;
 * this is the administrative surface a human deliberately invokes.
 */
@Service
public class AppUserService {

    private final AppUserRepository users;
    private final AccessGrantRepository grants;
    /** VYB-0700: so a directory row and its first role are one action. No cycle — AccessGrantService depends on the repository, not on this. */
    private final AccessGrantService accessGrants;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AppUserService(AppUserRepository users, AccessGrantRepository grants,
                          AccessGrantService accessGrants, JdbcTemplate jdbc, AuditService audit) {
        this.users = users;
        this.grants = grants;
        this.accessGrants = accessGrants;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * The {@code subject} an administrator-created user carries until the real person
     * signs in for the first time.
     *
     * <p>Vyoog does not create identities — Keycloak owns those, and nothing here sets or
     * could set a credential. What this creates is the local mirror row <em>early</em>,
     * so an administrator can assign grants and a manager to someone before their first
     * sign-in rather than having to wait for it and remember to come back. {@code
     * subject} is NOT NULL UNIQUE in the schema and the real Keycloak {@code sub} isn't
     * known yet, so the row holds a placeholder until {@link
     * UserProvisioningService#upsert} claims it.
     *
     * <p>The prefix is what makes a row claimable. A row whose subject does not start
     * with this has a real Keycloak subject behind it and must never be adopted by an
     * email match — that would let anyone whose IdP email happens to collide inherit
     * another person's grants.
     */
    public static final String UNCLAIMED_SUBJECT_PREFIX = "unclaimed:";

    public record DirectoryRow(AppUser user, long activeGrantCount) {}

    /**
     * VYB-0700: an administrator adds a colleague to the directory before that person
     * has ever signed in. {@code source} is LOCAL — one of the three the schema's own
     * CHECK constraint already allows, and the honest label for a row a human typed
     * rather than one an identity provider supplied.
     *
     * <p>A repeated email is allowed. It is not free: {@link UserProvisioningService}
     * claims a prepared row by email, and it will not guess between two candidates — with
     * more than one unclaimed row on an address, none is claimed and the person's sign-in
     * creates a fresh row instead, leaving whatever roles were prepared sitting on rows
     * nobody is using. Duplicates are a directory to tidy up, not an error to refuse.
     */
    @Transactional
    public AppUser createLocal(String email, String displayName, UUID actor) {
        String cleanEmail = email == null ? "" : email.strip();
        String cleanName = displayName == null ? "" : displayName.strip();
        if (cleanEmail.isBlank()) throw new IllegalArgumentException("An email address is required.");
        if (cleanName.isBlank()) throw new IllegalArgumentException("A display name is required.");
        AppUser user = new AppUser(UNCLAIMED_SUBJECT_PREFIX + UUID.randomUUID(), cleanEmail, cleanName);
        user.markLocallyCreated();
        AppUser saved = users.save(user);
        audit.record(actor, "user.created", "APP_USER", saved.getId(), null,
            java.util.Map.of("email", cleanEmail, "displayName", cleanName, "source", saved.getSource()));
        return saved;
    }

    /**
     * VYB-0700: creates the directory row and its first role in one action, atomically.
     *
     * <p>The role is Vyoog's own {@code access_grant}, not a Keycloak role, and nothing
     * about it waits on or consults the identity provider — it applies from the moment
     * this returns. Giving someone DEVELOPER or TESTER here is complete; the sign-in that
     * later claims the row changes nothing about what they may do.
     *
     * <p>What the identity provider still owns, and this cannot change, is whether they
     * can sign in at all. That is authentication, and Vyoog holds no credential to make
     * it with — see the no-passwords rule in CLAUDE.md.
     *
     * <p>One transaction on purpose: a directory row created and then left role-less
     * because the grant was refused is the confusing half-state this method exists to
     * avoid. Either both land or neither does.
     */
    @Transactional
    public AppUser createLocalWithRole(String email, String displayName, AccessRole role,
                                       ScopeType scopeType, UUID scopeId, UUID actor) {
        AppUser saved = createLocal(email, displayName, actor);
        if (role != null) {
            accessGrants.grant(saved.getId(), role, scopeType == null ? ScopeType.PLATFORM : scopeType,
                scopeId, null, actor);
        }
        return saved;
    }

    /** VYB-0700/0750: source, status and grant count, listable without a per-user round trip each. */
    public List<DirectoryRow> directory() {
        List<AppUser> all = users.findAll();
        return all.stream().map(u -> new DirectoryRow(u, activeGrantCount(u.getId()))).toList();
    }

    public DirectoryRow directoryRow(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(NoSuchElementException::new);
        return new DirectoryRow(user, activeGrantCount(userId));
    }

    public record PickerRow(UUID id, String displayName, String email) {}

    /**
     * VYB-0502: {@link #directory()} carries status, grant counts, delegate id — real
     * administrative detail an ADMINISTRATOR-only screen needs, and exactly why
     * assigning a developer/owner/tester was still "paste an ID": nothing short of
     * that heavier, gated endpoint existed for an ordinary authenticated user to find
     * a name. This is deliberately minimal — id/name/email, nothing an assigning user
     * shouldn't already be able to see about a colleague — and open to anyone already
     * past {@code SecurityConfig}'s {@code anyRequest().authenticated()}, not gated
     * behind {@code requireAdministrator}. Active people only: a departed account
     * isn't someone you'd pick to assign new work to.
     */
    public List<PickerRow> picker(String query) {
        List<AppUser> candidates = query == null || query.isBlank()
            ? users.findAll()
            : users.findAllByDisplayNameContainingIgnoreCaseOrEmailContainingIgnoreCase(query, query);
        return candidates.stream()
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .sorted(java.util.Comparator.comparing(AppUser::getDisplayName))
            .limit(50)
            .map(u -> new PickerRow(u.getId(), u.getDisplayName(), u.getEmail()))
            .toList();
    }

    private long activeGrantCount(UUID userId) {
        Long n = jdbc.queryForObject(
            "SELECT count(*) FROM access_grant WHERE user_id = ? AND revoked_at IS NULL " +
            "AND (expires_at IS NULL OR expires_at > now())", Long.class, userId);
        return n == null ? 0 : n;
    }

    @Transactional
    public AppUser setStatus(UUID userId, String status, UUID actor) {
        AppUser user = users.findById(userId).orElseThrow(NoSuchElementException::new);
        String before = user.getStatus();
        user.setStatus(status);
        users.save(user);
        audit.record(actor, "user.status-changed", "APP_USER", userId,
            java.util.Map.of("status", before), java.util.Map.of("status", status));
        return user;
    }

    /** VYB-0706: a user on leave nominates who takes their derived tasks. Accountability is untouched — nothing here reassigns ownership of anything. */
    @Transactional
    public AppUser setDelegate(UUID userId, UUID delegateId, UUID actor) {
        AppUser user = users.findById(userId).orElseThrow(NoSuchElementException::new);
        if (delegateId != null && !users.existsById(delegateId)) {
            throw new IllegalArgumentException("No such delegate");
        }
        // The same guard setManager has carried since VYB-0792, missing here. Delegation
        // exists so a user's derived tasks reach someone else while they are on leave;
        // pointing it at themselves routes that work straight back to the person who is
        // away, which is precisely the outcome the feature exists to prevent. The picker
        // already excludes self, so only a direct API call could reach this.
        if (delegateId != null && delegateId.equals(userId)) {
            throw new IllegalArgumentException("A user cannot be their own delegate");
        }
        user.setDelegateId(delegateId);
        users.save(user);
        audit.record(actor, "user.delegate-set", "APP_USER", userId, null,
            java.util.Map.of("delegateId", String.valueOf(delegateId)));
        return user;
    }

    public List<AppUser> delegatesFor(UUID userId) {
        return users.findAllByDelegateId(userId);
    }

    /** VYB-0334/0792: an administrator records who manages whom — the only real input {@code ClarificationService}'s escalation chain needed to close its own disclosed gap. */
    @Transactional
    public AppUser setManager(UUID userId, UUID managerId, UUID actor) {
        AppUser user = users.findById(userId).orElseThrow(NoSuchElementException::new);
        if (managerId != null && !users.existsById(managerId)) {
            throw new IllegalArgumentException("No such manager");
        }
        if (managerId != null && managerId.equals(userId)) {
            throw new IllegalArgumentException("A user cannot be their own manager");
        }
        user.setManagerId(managerId);
        users.save(user);
        audit.record(actor, "user.manager-set", "APP_USER", userId, null,
            java.util.Map.of("managerId", String.valueOf(managerId)));
        return user;
    }

    public Optional<AppUser> find(UUID userId) {
        return users.findById(userId);
    }
}
