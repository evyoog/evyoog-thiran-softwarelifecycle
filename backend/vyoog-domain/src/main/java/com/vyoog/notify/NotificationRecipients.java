package com.vyoog.notify;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeRef;
import com.vyoog.identity.ScopeType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0955 (D47): who an event is for. "Whoever it is now waiting on" is the people who hold the role that must act, at or above the
 * place the work sits (the same walk up from capability to application to product to platform that decides whether someone may act,
 * {@link GrantResolver#ancestorChain}), and only people who are active and whose grant is live. Someone on leave or departed is not
 * told; someone whose grant has expired or been revoked is not told. Returned in a fixed order so that two writes notifying the same
 * people always lock their inbox rows in the same order.
 */
@Service
public class NotificationRecipients {

    private final JdbcTemplate jdbc;
    private final GrantResolver grants;

    public NotificationRecipients(JdbcTemplate jdbc, GrantResolver grants) {
        this.jdbc = jdbc;
        this.grants = grants;
    }

    /** Where a requirement sits for the purpose of who may act on it: its capability, else its application, else its product, else the platform. */
    public static ScopeRef placementOf(UUID capabilityId, UUID applicationId, UUID productId) {
        if (capabilityId != null) return new ScopeRef(ScopeType.CAPABILITY, capabilityId);
        if (applicationId != null) return new ScopeRef(ScopeType.APP, applicationId);
        if (productId != null) return new ScopeRef(ScopeType.PRODUCT, productId);
        return ScopeRef.platform();
    }

    /** People holding {@code role} effectively at any of the places: a grant at the place itself or anywhere above it. */
    public Set<UUID> holders(AccessRole role, Collection<ScopeRef> places) {
        Set<ScopeRef> chain = new LinkedHashSet<>();
        for (ScopeRef p : places) chain.addAll(grants.ancestorChain(p.type(), p.id()));
        if (chain.isEmpty()) return new TreeSet<>();
        List<Object> args = new ArrayList<>();
        args.add(role.name());
        StringBuilder where = new StringBuilder();
        for (ScopeRef ref : chain) {
            if (where.length() > 0) where.append(" OR ");
            if (ref.type() == ScopeType.PLATFORM) {
                where.append("g.scope_type = 'PLATFORM'");
            } else {
                where.append("(g.scope_type = ? AND g.scope_id = ?)");
                args.add(ref.type().name());
                args.add(ref.id());
            }
        }
        List<UUID> ids = jdbc.queryForList("""
            SELECT DISTINCT g.user_id FROM access_grant g JOIN app_user u ON u.id = g.user_id
             WHERE g.role = ? AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > now())
               AND u.status = 'ACTIVE' AND (%s)
            """.formatted(where), UUID.class, args.toArray());
        return new TreeSet<>(ids);
    }

    public Set<UUID> holders(AccessRole role, ScopeRef place) {
        return holders(role, List.of(place));
    }

    /** Of these people, those who are active (not on leave or departed). */
    public Set<UUID> active(Collection<UUID> userIds) {
        if (userIds.isEmpty()) return new TreeSet<>();
        String in = String.join(",", userIds.stream().map(u -> "?").toList());
        return new TreeSet<>(jdbc.queryForList("SELECT id FROM app_user WHERE status = 'ACTIVE' AND id IN (" + in + ")",
            UUID.class, userIds.toArray()));
    }
}
