package com.vyoog.identity;

import com.vyoog.platform.audit.AuditService;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0961 (D49): an administrator joins a prepared directory row to the account its person actually signed in with.
 *
 * <p>A first sign-in claims the prepared row by <em>email address</em> (VYB-0700). When the address Keycloak sends is not the one the
 * administrator typed (a nickname, another domain, a typo), the person gets a second, empty account and no access, and the prepared row,
 * with its grants, sits unclaimed. This is the repair, and it is never automatic and never guessed: the administrator names both.
 *
 * <p>What it does: the signed-in account's Keycloak subject, address and sign-in details move onto the prepared row (so the grants, team
 * memberships, manager and delegate the administrator prepared are the person's from their next request), and the empty account is
 * deleted. What it refuses, so that nothing of anyone's can be lost: an account that is not a real sign-in, a prepared row that is
 * already claimed, an account that holds any grant or belongs to any team or has saved views, and an account that has done anything in
 * Vyoog (every table that records who did something refers to the account, and the database refuses to delete one that is referred to).
 */
@Service
public class AccountClaimService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AppUserRepository users;

    public AccountClaimService(JdbcTemplate jdbc, AuditService audit, AppUserRepository users) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.users = users;
    }

    @Transactional
    public AppUser claim(UUID preparedId, UUID accountId, UUID actor) {
        if (preparedId.equals(accountId)) {
            throw new IllegalArgumentException("Pick two different directory entries: the prepared one, and the account the person signed in with.");
        }
        jdbc.queryForList("SELECT id FROM app_user WHERE id IN (?, ?) ORDER BY id FOR UPDATE", UUID.class, preparedId, accountId);
        Map<String, Object> prepared = row(preparedId);
        Map<String, Object> account = row(accountId);

        if (!String.valueOf(prepared.get("subject")).startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX)) {
            throw new IllegalStateException(prepared.get("email") + " has already signed in, so there is nothing to claim.");
        }
        if (String.valueOf(account.get("subject")).startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX)) {
            throw new IllegalStateException(account.get("email") + " is a prepared entry, not an account anyone has signed in with.");
        }
        refuseIfHolds(accountId, "SELECT count(*) FROM access_grant WHERE user_id = ?", "holds access grants (a person with access is not merged away)");
        refuseIfHolds(accountId, "SELECT count(*) FROM team_member WHERE user_id = ?", "belongs to a team");
        refuseIfHolds(accountId, "SELECT count(*) FROM saved_view WHERE owner_id = ?", "has saved views");

        String subject = (String) account.get("subject");
        String accountEmail = (String) account.get("email");
        Object lastSeen = account.get("last_seen_at");
        boolean mfa = Boolean.TRUE.equals(account.get("mfa_enrolled"));
        try {
            jdbc.update("DELETE FROM app_user WHERE id = ?", accountId);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(accountEmail + " has already done work in Vyoog (" + whatRefersToIt(e)
                + "), so it is not merged away. An account that has only signed in can be.");
        }
        jdbc.update("""
            UPDATE app_user SET subject = ?, source = 'SSO', email = ?, last_seen_at = ?, mfa_enrolled = ? WHERE id = ?
            """, subject, accountEmail, lastSeen, mfa, preparedId);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("email", prepared.get("email"));
        before.put("signedIn", false);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("email", accountEmail);
        after.put("signedIn", true);
        after.put("mergedAccountId", accountId.toString());
        audit.record(actor, "user.account_claimed", "APP_USER", preparedId, before, after);
        return users.findById(preparedId).orElseThrow(NoSuchElementException::new);
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, subject, email, source, last_seen_at, mfa_enrolled FROM app_user WHERE id = ?", id);
        if (rows.isEmpty()) throw new NoSuchElementException("No such user: " + id);
        return rows.get(0);
    }

    private void refuseIfHolds(UUID accountId, String countSql, String what) {
        Long n = jdbc.queryForObject(countSql, Long.class, accountId);
        if (n != null && n > 0) {
            throw new IllegalStateException("That account " + what + ", so it is not merged away. An account that has only signed in can be.");
        }
    }

    private static final java.util.regex.Pattern REFERRER = java.util.regex.Pattern.compile("constraint \"[^\"]*\" on table \"([a-z_]+)\"");

    /** The table that still refers to the account, from the database's own refusal; no constraint text in the answer. */
    private static String whatRefersToIt(DataIntegrityViolationException e) {
        Throwable t = e;
        while (t != null) {
            var m = REFERRER.matcher(String.valueOf(t.getMessage()));
            if (m.find()) return "it is named on " + m.group(1).replace('_', ' ');
            t = t.getCause();
        }
        return "something refers to it";
    }
}
