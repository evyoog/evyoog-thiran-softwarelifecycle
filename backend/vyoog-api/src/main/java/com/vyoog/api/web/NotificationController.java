package com.vyoog.api.web;

import com.vyoog.api.notify.NotificationSseRegistry;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.notify.Notification;
import com.vyoog.notify.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** VYB-0355/0791: the inbox itself, plus the real-time stream — how each item got there is the domain services that call {@code NotificationService}. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final UserProvisioningService provisioning;
    private final NotificationSseRegistry sse;

    public NotificationController(NotificationService notifications, UserProvisioningService provisioning,
                                   NotificationSseRegistry sse) {
        this.notifications = notifications;
        this.provisioning = provisioning;
        this.sse = sse;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record NotificationView(
        String id, String tone, String kind, String title, String subtitle, String link, boolean read,
        int occurrenceCount, String createdAt) {}

    private static NotificationView toView(Notification n) {
        return new NotificationView(n.getId().toString(), n.getTone(), n.getKind(), n.getTitle(), n.getSubtitle(),
            n.getLink(), n.getReadAt() != null, n.getOccurrenceCount(), n.getCreatedAt().toString());
    }

    @GetMapping
    public List<NotificationView> mine(@AuthenticationPrincipal Jwt jwt) {
        return notifications.inbox(currentUserId(jwt)).stream().map(NotificationController::toView).toList();
    }

    @GetMapping("/unread-count")
    public long unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return notifications.unreadCount(currentUserId(jwt));
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        notifications.markRead(id, currentUserId(jwt));
    }

    /**
     * VYB-0791: real-time push, closing VYB-0356's "events, not polling" — the
     * frontend still calls this via {@code fetch()} with a real {@code Authorization}
     * header rather than the browser's native {@code EventSource} API, since
     * {@code EventSource} can't send custom headers at all; the only way to attach a
     * bearer token to it would be a token in the URL's query string, which this
     * codebase has avoided everywhere else a token is handled (see AuthProvider.tsx).
     * No timeout set — the connection is meant to live as long as the tab does; the
     * browser reconnects on its own if it drops.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        SseEmitter emitter = new SseEmitter(0L);
        sse.register(currentUserId(jwt), emitter);
        return emitter;
    }
}
