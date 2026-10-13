package com.vyoog.api.it;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.notify.NotificationDeliveryQueue;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * VYB-0955: the base of the notification tests. The application is started with email and chat set up, pointing at a fake mail
 * server and a fake chat webhook on this machine (see {@link FakeSmtpServer}, {@link FakeWebhook}), so that what is queued can also be
 * seen to go out. One context serves every subclass.
 *
 * <p>The scheduled sender waits 30 seconds before its first pass, so a test's own {@code deliver()} call is the one that sends; passes
 * never overlap ({@code deliver} is synchronized), so a message is never sent twice.
 */
abstract class NotificationITBase extends IntegrationTestBase {

    static final FakeSmtpServer SMTP = new FakeSmtpServer();
    static final FakeWebhook CHAT = new FakeWebhook();
    static final String BASE_URL = "http://app.test";

    @DynamicPropertySource
    static void channels(DynamicPropertyRegistry registry) {
        registry.add("vyoog.notify.email.host", () -> "127.0.0.1");
        registry.add("vyoog.notify.email.port", SMTP::port);
        registry.add("vyoog.notify.email.from", () -> "vyoog@app.test");
        registry.add("vyoog.notify.chat.teams-webhook-url", CHAT::url);
        registry.add("vyoog.notify.public-base-url", () -> BASE_URL);
    }

    @Autowired protected NotificationDeliveryQueue queue;

    /** The inbox rows a person has of one kind. */
    protected int inbox(UUID user, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ? AND kind = ?", Integer.class, user, kind);
    }

    protected int inboxTotal(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Integer.class, user);
    }

    protected String subtitleOf(UUID user, String kind) {
        return jdbc.queryForObject("SELECT subtitle FROM notification WHERE user_id = ? AND kind = ? ORDER BY created_at DESC LIMIT 1", String.class, user, kind);
    }

    protected UUID grant(UUID user, AccessRole role, ScopeType type, UUID scope) {
        grants.grant(user, role, type, scope, null, user);
        return user;
    }

    protected Requirement submit(Requirement r, UUID author) {
        return requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW,
            "no criteria yet, submitted on purpose", author);
    }

    protected Requirement move(Requirement r, RequirementStatus to, String reason, UUID actor) {
        return requirementService.transition(r.getId(), r.getRevision(), to, reason, actor);
    }

    /** Email deliveries queued for one person, newest last. */
    protected List<String> emailTitles(UUID user) {
        return jdbc.queryForList("SELECT title FROM notification_delivery WHERE channel = 'EMAIL' AND user_id = ? ORDER BY created_at", String.class, user);
    }

    /** Pretends the digest window has passed for everything this person has waiting. */
    protected void ageDeliveries(UUID user) {
        jdbc.update("UPDATE notification_delivery SET due_at = now() - interval '1 minute' WHERE user_id = ? AND state = 'PENDING'", user);
    }

    protected static Placement place(Portfolio p) { return Placement.capability(p.capabilityId()); }
}
