package com.vyoog.notify;

import com.vyoog.platform.audit.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0955 (D47): the administrator's side: which kinds of event are posted to the team chat channel, and whether email and chat are
 * set up at all. The channel's address and the mail server are in the environment (rule 9), so this reports only whether each is
 * configured, never the value.
 */
@Service
public class NotificationSettingsService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotifyProperties properties;
    private final NotificationDeliveryQueue queue;

    public NotificationSettingsService(JdbcTemplate jdbc, AuditService audit, NotifyProperties properties, NotificationDeliveryQueue queue) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.properties = properties;
        this.queue = queue;
    }

    public record ChatKindView(String kind, String label, String group, boolean enabled) {}

    public record View(boolean emailConfigured, boolean chatConfigured, boolean linksConfigured, List<ChatKindView> chatKinds,
                       NotificationDeliveryQueue.Stats email, NotificationDeliveryQueue.Stats chat) {}

    public View view() {
        Set<String> on = chatKindsOn();
        List<ChatKindView> kinds = new ArrayList<>();
        for (NotificationKind k : NotificationKind.values()) {
            if (k.chatEligible()) kinds.add(new ChatKindView(k.key(), k.label(), k.group(), on.contains(k.key())));
        }
        return new View(properties.emailEnabled(), properties.chatEnabled(), !properties.publicBaseUrl().isEmpty(), kinds,
            queue.stats(NotificationChannel.EMAIL), queue.stats(NotificationChannel.CHAT));
    }

    public Set<String> chatKindsOn() {
        return jdbc.queryForList("SELECT kind FROM notification_chat_kind", String.class).stream().collect(Collectors.toSet());
    }

    public boolean chatKindEnabled(String kind) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM notification_chat_kind WHERE kind = ?)", Boolean.class, kind));
    }

    /** Replaces the set of kinds posted to chat. Only chat-eligible kinds may be named. */
    @Transactional
    public View setChatKinds(List<String> kinds, UUID actor) {
        Set<String> wanted = new java.util.LinkedHashSet<>();
        for (String key : kinds == null ? List.<String>of() : kinds) {
            NotificationKind k = NotificationKind.find(key).orElseThrow(() -> new IllegalArgumentException("No such kind of notification: " + key));
            if (!k.chatEligible()) {
                throw new IllegalArgumentException(k.label() + " is personal to the person it is for and cannot be posted to a team channel");
            }
            wanted.add(k.key());
        }
        Set<String> before = chatKindsOn();
        for (String k : before) {
            if (!wanted.contains(k)) jdbc.update("DELETE FROM notification_chat_kind WHERE kind = ?", k);
        }
        for (String k : wanted) {
            jdbc.update("INSERT INTO notification_chat_kind (kind, enabled_by) VALUES (?, ?) ON CONFLICT (kind) DO NOTHING", k, actor);
        }
        if (!before.equals(wanted)) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("chatKinds", before.stream().sorted().toList());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("chatKinds", wanted.stream().sorted().toList());
            audit.record(actor, "notification.chat-kinds-changed", "APP_CONFIG", null, b, a);
        }
        return view();
    }
}
