package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.RequiresAccess;
import com.vyoog.api.notify.NotificationDeliveryService;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.notify.NotificationChannel;
import com.vyoog.notify.NotificationDeliveryQueue;
import com.vyoog.notify.NotificationSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0955 (D47): the administrator's view of how notifications leave the app: whether email and chat are set up (never the mail
 * password or the chat address), which kinds are posted to the team channel, and how the queue is doing. Administrator only.
 */
@RestController
@RequestMapping("/api/v1/settings/notifications")
public class NotificationSettingsController {

    private final NotificationSettingsService settings;
    private final NotificationDeliveryService delivery;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public NotificationSettingsController(NotificationSettingsService settings, NotificationDeliveryService delivery,
                                          UserProvisioningService provisioning, PrincipalGuard guard) {
        this.settings = settings;
        this.delivery = delivery;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    public record NotificationChatKindView(String kind, String label, String group, boolean enabled) {}
    public record NotificationChannelStats(int pending, int sentLast24h, int failedLast24h, String lastError) {}
    public record NotificationSettingsView(boolean emailConfigured, boolean chatConfigured, boolean linksConfigured,
                               List<NotificationChatKindView> chatKinds, NotificationChannelStats email, NotificationChannelStats chat) {}
    public record SetNotificationChatKinds(List<String> kinds) {}
    public record NotificationTestRequest(@NotBlank String channel) {}
    public record NotificationTestResultView(boolean ok, String detail) {}

    private static NotificationChannelStats toView(NotificationDeliveryQueue.Stats s) {
        return new NotificationChannelStats(s.pending(), s.sentLast24h(), s.failedLast24h(), s.lastError());
    }

    private static NotificationSettingsView toView(NotificationSettingsService.View v) {
        return new NotificationSettingsView(v.emailConfigured(), v.chatConfigured(), v.linksConfigured(),
            v.chatKinds().stream().map(k -> new NotificationChatKindView(k.kind(), k.label(), k.group(), k.enabled())).toList(),
            toView(v.email()), toView(v.chat()));
    }

    private AppUser currentUser(Jwt jwt) {
        return provisioning.upsert(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
    }

    @GetMapping
    public NotificationSettingsView current(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return toView(settings.view());
    }

    @RequiresAccess(AccessRule.ADMIN)
    @PutMapping("/chat-kinds")
    public NotificationSettingsView setChatKinds(@RequestBody SetNotificationChatKinds body, @AuthenticationPrincipal Jwt jwt) {
        return toView(settings.setChatKinds(body.kinds(), currentUser(jwt).getId()));
    }

    /** Sends one message straight to the channel so the administrator can see it work; email goes to the caller's own address. */
    @RequiresAccess(AccessRule.ADMIN)
    @PostMapping("/test")
    public NotificationTestResultView test(@RequestBody @Valid NotificationTestRequest body, @AuthenticationPrincipal Jwt jwt) {
        NotificationChannel channel;
        try {
            channel = NotificationChannel.valueOf(body.channel());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("No such channel: " + body.channel());
        }
        var result = delivery.sendTest(channel, currentUser(jwt).getEmail());
        return new NotificationTestResultView(result.ok(), result.detail());
    }
}
