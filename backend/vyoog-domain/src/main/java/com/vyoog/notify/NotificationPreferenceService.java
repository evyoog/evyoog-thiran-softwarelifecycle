package com.vyoog.notify;

import com.vyoog.platform.audit.AuditService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0955 (D47): each person's choice of which kinds of notification they want in the inbox and by email. A row is an explicit
 * choice; with none the kind's default applies (inbox on for every kind; email on for urgent kinds only). Changing a choice is
 * audited. Chat is not a personal choice (see {@link NotificationSettingsService}).
 */
@Service
public class NotificationPreferenceService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotifyProperties properties;

    public NotificationPreferenceService(JdbcTemplate jdbc, AuditService audit, NotifyProperties properties) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.properties = properties;
    }

    public record KindView(String kind, String label, String group, String description, boolean urgent,
                           boolean inbox, boolean email, boolean inboxIsDefault, boolean emailIsDefault) {}

    public record View(boolean emailAvailable, List<KindView> kinds) {}

    /** One change: {@code enabled} null puts the kind back to its default. */
    public record Change(String kind, NotificationChannel channel, Boolean enabled) {}

    public View view(UUID userId) {
        Map<String, Boolean> chosen = chosen(userId);
        List<KindView> kinds = new ArrayList<>();
        for (NotificationKind k : NotificationKind.values()) {
            Boolean inbox = chosen.get(k.key() + "|INBOX");
            Boolean email = chosen.get(k.key() + "|EMAIL");
            kinds.add(new KindView(k.key(), k.label(), k.group(), k.description(), k.urgent(),
                inbox == null ? NotificationKind.defaultInbox(k.key()) : inbox,
                email == null ? NotificationKind.defaultEmail(k.key()) : email,
                inbox == null, email == null));
        }
        return new View(properties.emailEnabled(), kinds);
    }

    private Map<String, Boolean> chosen(UUID userId) {
        Map<String, Boolean> out = new HashMap<>();
        jdbc.query("SELECT kind, channel, enabled FROM notification_preference WHERE user_id = ?",
            rs -> { out.put(rs.getString("kind") + "|" + rs.getString("channel"), rs.getBoolean("enabled")); }, userId);
        return out;
    }

    /** Whether this person wants this kind on this channel (their choice, else the default). Only INBOX and EMAIL are personal. */
    public boolean wants(UUID userId, String kind, NotificationChannel channel) {
        List<Boolean> rows = jdbc.queryForList(
            "SELECT enabled FROM notification_preference WHERE user_id = ? AND kind = ? AND channel = ?",
            Boolean.class, userId, kind, channel.name());
        if (!rows.isEmpty()) return rows.get(0);
        return channel == NotificationChannel.EMAIL ? NotificationKind.defaultEmail(kind) : NotificationKind.defaultInbox(kind);
    }

    /** The choices of several people for one kind at once, keyed by user (those with no choice are absent). */
    public Map<UUID, Boolean> chosenFor(Collection<UUID> userIds, String kind, NotificationChannel channel) {
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) return out;
        String in = String.join(",", userIds.stream().map(u -> "?").toList());
        List<Object> args = new ArrayList<>(userIds);
        args.add(kind);
        args.add(channel.name());
        jdbc.query("SELECT user_id, enabled FROM notification_preference WHERE user_id IN (" + in + ") AND kind = ? AND channel = ?",
            rs -> { out.put(rs.getObject("user_id", UUID.class), rs.getBoolean("enabled")); }, args.toArray());
        return out;
    }

    @Transactional
    public View change(UUID userId, List<Change> changes, UUID actor) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (Change c : changes) {
            if (c.channel() == null || c.channel() == NotificationChannel.CHAT) {
                throw new IllegalArgumentException("A person can choose INBOX or EMAIL; chat is set by an administrator");
            }
            NotificationKind kind = NotificationKind.find(c.kind())
                .orElseThrow(() -> new IllegalArgumentException("No such kind of notification: " + c.kind()));
            String label = kind.key() + "/" + c.channel().name();
            before.put(label, current(userId, kind.key(), c.channel()));
            if (c.enabled() == null) {
                jdbc.update("DELETE FROM notification_preference WHERE user_id = ? AND kind = ? AND channel = ?",
                    userId, kind.key(), c.channel().name());
            } else {
                jdbc.update("""
                    INSERT INTO notification_preference (user_id, kind, channel, enabled) VALUES (?, ?, ?, ?)
                    ON CONFLICT (user_id, kind, channel) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                    """, userId, kind.key(), c.channel().name(), c.enabled());
            }
            after.put(label, current(userId, kind.key(), c.channel()));
        }
        if (!changes.isEmpty()) {
            audit.record(actor, "notification.preferences-changed", "USER", userId, before, after);
        }
        return view(userId);
    }

    private boolean current(UUID userId, String kind, NotificationChannel channel) {
        return wants(userId, kind, channel);
    }
}
