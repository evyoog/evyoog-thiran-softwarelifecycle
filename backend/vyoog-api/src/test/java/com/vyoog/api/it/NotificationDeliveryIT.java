package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.api.notify.NotificationDeliveryService;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.notify.NotificationChannel;
import com.vyoog.notify.NotificationDeliveryQueue;
import com.vyoog.notify.NotificationPreferenceService;
import com.vyoog.notify.NotificationService;
import com.vyoog.notify.NotificationSettingsService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0955 (F21, D47): what leaves the application. Email goes to a fake mail server and chat to a fake webhook, both on this machine
 * (the real ones are out of reach here); each test makes its own people, and finds its own messages by who they were for.
 */
class NotificationDeliveryIT extends NotificationITBase {

    @Autowired NotificationDeliveryService delivery;
    @Autowired NotificationPreferenceService preferences;
    @Autowired NotificationSettingsService chatSettings;
    @Autowired NotificationService notifications;
    @Autowired VerificationService verification;

    /** Sends everything that is due, as the scheduled job would over several passes (a pass takes at most 50). */
    private void deliverAll() {
        for (int i = 0; i < 40; i++) {
            var r = delivery.deliver();
            if (r.emailsSent() + r.chatPostsSent() + r.failed() == 0) return;
        }
    }

    /** Whatever earlier tests left queued goes out first, so each test counts only its own messages. */
    @BeforeEach
    void flushLeftovers() {
        deliverAll();
    }

    @AfterEach
    void putTheFakeServersBack() {
        SMTP.refuseEverything(false);
        CHAT.answerWith(202);
    }

    private List<FakeSmtpServer.Message> mailTo(UUID user) {
        String email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, user);
        return SMTP.received().stream().filter(m -> m.to().contains(email)).toList();
    }

    private record Scene(Portfolio place, UUID author, UUID reviewer, UUID approver, UUID admin) {}

    private Scene scene() {
        Portfolio p = newPortfolio();
        return new Scene(p, newUser("author"), grant(newUser("reviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId()),
            grant(newUser("approver"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId()), newAdministrator());
    }

    // ------------------------------------------------------------------ email

    @Test
    void VYB0955_AC10_anUrgentKindIsEmailedByDefaultOnceWithTheWordsAndALinkAndNotAgain() {
        Scene s = scene();
        Requirement r = submit(newRequirement(s.place(), s.author()), s.author());

        deliverAll();

        var mail = mailTo(s.reviewer());
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).subject()).isEqualTo("[Vyoog] Review requested: " + r.getKey());
        assertThat(mail.get(0).from()).isEqualTo("vyoog@app.test");
        assertThat(mail.get(0).body()).contains("Review requested: " + r.getKey()).contains(r.getTitle())
            .contains(BASE_URL + "/requirements/" + r.getId()).contains("notification choices");
        deliverAll();
        assertThat(mailTo(s.reviewer())).as("sent once").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE user_id = ?", String.class, s.reviewer())).isEqualTo("SENT");
    }

    @Test
    void VYB0955_AC11_aPersonWhoSwitchedEmailOffForAKindIsNotEmailedAndOneWhoSwitchedItOnForAQuietKindGetsItInADigest() {
        Scene s = scene();
        preferences.change(s.reviewer(), List.of(new NotificationPreferenceService.Change("review-requested", NotificationChannel.EMAIL, false)), s.reviewer());
        preferences.change(s.author(), List.of(new NotificationPreferenceService.Change("requirement-approved", NotificationChannel.EMAIL, true),
            new NotificationPreferenceService.Change("test-failed", NotificationChannel.EMAIL, true)), s.author());
        Requirement r = submit(newRequirement(s.place(), s.author()), s.author());
        Requirement reviewed = move(r, RequirementStatus.REVIEWED, null, s.reviewer());
        move(reviewed, RequirementStatus.APPROVED, null, s.approver());
        var run = verification.findOrCreateRun(unique("build"), unique("ci"));
        verification.ingest(run, new VerificationService.ResultInput(unique("TC"), "Punch capture", VerificationResult.FAIL, List.of(r.getKey())));

        deliverAll();
        assertThat(mailTo(s.reviewer())).as("switched off").isEmpty();
        assertThat(mailTo(s.author())).as("quiet kinds wait for the digest window").isEmpty();
        assertThat(emailTitles(s.author())).hasSize(2);

        ageDeliveries(s.author());
        deliverAll();

        var mail = mailTo(s.author());
        assertThat(mail).as("both go in one message").hasSize(1);
        assertThat(mail.get(0).subject()).isEqualTo("[Vyoog] 2 updates for you");
        assertThat(mail.get(0).body()).contains(r.getKey() + " was approved").contains("Test failed against " + r.getKey());
    }

    @Test
    void VYB0955_AC12_aSecondUrgentOneOfTheSameKindInTheWindowWaitsForTheDigestAndIsNotLost() {
        Scene s = scene();
        Requirement first = submit(newRequirement(s.place(), s.author()), s.author());
        Requirement second = submit(newRequirement(s.place(), s.author()), s.author());

        deliverAll();
        assertThat(mailTo(s.reviewer())).as("only the first is immediate").hasSize(1);
        assertThat(mailTo(s.reviewer()).get(0).subject()).contains(first.getKey());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE user_id = ? AND state = 'PENDING'", Integer.class, s.reviewer())).isEqualTo(1);

        ageDeliveries(s.reviewer());
        deliverAll();

        var mail = mailTo(s.reviewer());
        assertThat(mail).hasSize(2);
        assertThat(mail.get(1).body()).contains(second.getKey());
    }

    @Test
    void VYB0955_AC13_aMessageTheMailServerRefusesIsRecordedAsFailedTriedAgainAndLeftFailedAfterTheLastTry() {
        Scene s = scene();
        submit(newRequirement(s.place(), s.author()), s.author());
        SMTP.refuseEverything(true);

        deliverAll();
        assertThat(mailTo(s.reviewer())).isEmpty();
        var row = jdbc.queryForMap("SELECT state, attempts, last_error FROM notification_delivery WHERE user_id = ?", s.reviewer());
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();

        SMTP.refuseEverything(false);
        ageDeliveries(s.reviewer());
        deliverAll();
        assertThat(mailTo(s.reviewer())).as("tried again and arrived").hasSize(1);

        // and one that never gets through is left failed, not tried for ever
        Scene t = scene();
        submit(newRequirement(t.place(), t.author()), t.author());
        SMTP.refuseEverything(true);
        for (int i = 0; i < NotificationDeliveryQueue.MAX_ATTEMPTS; i++) {
            ageDeliveries(t.reviewer());
            deliverAll();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE user_id = ?", String.class, t.reviewer())).isEqualTo("FAILED");
        ageDeliveries(t.reviewer());
        int sent = SMTP.received().size();
        deliverAll();
        assertThat(SMTP.received().size()).as("a failed one is not tried again").isEqualTo(sent);
    }

    @Test
    void VYB0955_AC14_aTitleWithALineBreakCannotAddAHeaderAndEmailGoesToOnePersonOnly() {
        Scene s = scene();
        notifications.notify(s.reviewer(), "warn", "mentioned", "Hello\r\nBcc: someone@else.test", "body", "/requirements/1");

        deliverAll();

        var mail = mailTo(s.reviewer());
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).subject()).isEqualTo("[Vyoog] Hello Bcc: someone@else.test");
        assertThat(mail.get(0).to()).hasSize(1);
        String headers = mail.get(0).data().substring(0, mail.get(0).data().indexOf("\r\n\r\n"));
        assertThat(headers.split("\r\n")).as("no header of its own was added").noneMatch(h -> h.startsWith("Bcc:"));
    }

    @Test
    void VYB0955_AC15_aPersonWithNoEmailAddressIsRecordedAsAFailureNotSilentlyDropped() {
        Scene s = scene();
        jdbc.update("UPDATE app_user SET email = '' WHERE id = ?", s.reviewer());
        submit(newRequirement(s.place(), s.author()), s.author());

        deliverAll();

        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE user_id = ?", String.class, s.reviewer()))
            .as("no address: not sent, and the error is kept where the administrator can see it").isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM notification_delivery WHERE user_id = ?", String.class, s.reviewer()))
            .contains("no email address");
    }

    // ------------------------------------------------------------------ chat

    @Test
    void VYB0955_AC16_chatGetsOnePostPerEventOnlyForTheKindsTheAdministratorSwitchedOnAndNoReasonOrCommentText() {
        Scene s = scene();
        chatSettings.setChatKinds(List.of("requirement-approved", "requirement-rejected"), s.admin());
        int before = CHAT.posts().size();
        Requirement a = submit(newRequirement(s.place(), s.author()), s.author());
        a = move(a, RequirementStatus.REVIEWED, null, s.reviewer());
        move(a, RequirementStatus.APPROVED, null, s.approver());
        Requirement b = submit(newRequirement(s.place(), s.author()), s.author());
        b = move(b, RequirementStatus.REVIEWED, null, s.reviewer());
        move(b, RequirementStatus.REJECTED, "Confidential: the customer will not pay for this", s.approver());

        deliverAll();

        List<String> posts = CHAT.posts().subList(before, CHAT.posts().size());
        assertThat(posts).as("one post for the approval and one for the rejection; none for review requests, which are not switched on").hasSize(2);
        assertThat(posts.get(0)).contains("AdaptiveCard").contains(a.getKey() + " was approved").contains("Open in Vyoog").contains(BASE_URL + "/requirements/" + a.getId());
        assertThat(posts.get(1)).contains(b.getKey() + " was rejected");
        assertThat(String.join("", posts)).as("a reason is never posted to a shared channel").doesNotContain("Confidential");
        chatSettings.setChatKinds(List.of(), s.admin());
    }

    @Test
    void VYB0955_AC17_everyFailingTestInOneRunIsOnePostToChat() {
        Scene s = scene();
        chatSettings.setChatKinds(List.of("test-failed"), s.admin());
        int before = CHAT.posts().size();
        Requirement r1 = newRequirement(s.place(), s.author());
        Requirement r2 = newRequirement(s.place(), s.author());
        var run = verification.findOrCreateRun(unique("build"), unique("ci"));
        verification.ingest(run, new VerificationService.ResultInput(unique("TC"), "One", VerificationResult.FAIL, List.of(r1.getKey())));
        verification.ingest(run, new VerificationService.ResultInput(unique("TC"), "Two", VerificationResult.FAIL, List.of(r2.getKey())));

        deliverAll();

        assertThat(CHAT.posts().size() - before).isEqualTo(1);
        assertThat(inbox(s.author(), "test-failed")).as("each author is still told in their own inbox").isEqualTo(1);
        chatSettings.setChatKinds(List.of(), s.admin());
    }

    @Test
    void VYB0955_AC18_aChannelThatAnswersWithAnErrorIsRecordedAndTheAddressNeverAppearsInTheError() {
        Scene s = scene();
        chatSettings.setChatKinds(List.of("review-requested"), s.admin());
        submit(newRequirement(s.place(), s.author()), s.author());
        CHAT.answerWith(500);

        deliverAll();

        var row = jdbc.queryForMap("SELECT state, attempts, last_error FROM notification_delivery WHERE channel = 'CHAT' ORDER BY created_at DESC LIMIT 1");
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat((String) row.get("last_error")).contains("HTTP 500").doesNotContain("secret-signature").doesNotContain(CHAT.url());
        assertThat(chatSettings.view().chat().lastError()).doesNotContain("secret-signature");
        CHAT.answerWith(202);
        jdbc.update("UPDATE notification_delivery SET due_at = now() - interval '1 minute' WHERE channel = 'CHAT' AND state = 'PENDING'");
        deliverAll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE channel = 'CHAT' AND state = 'PENDING'", Integer.class)).isZero();
        chatSettings.setChatKinds(List.of(), s.admin());
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    void VYB0955_AC19_sentAndFailedRowsOlderThanThirtyDaysAreRemovedAndPendingOnesNever() {
        Scene s = scene();
        submit(newRequirement(s.place(), s.author()), s.author());
        deliverAll();
        UUID pending = jdbc.queryForObject("""
            INSERT INTO notification_delivery (channel, kind, user_id, title, state, created_at) VALUES ('EMAIL', 'mentioned', ?, 'old pending', 'PENDING', now() - interval '60 days')
            RETURNING id""", UUID.class, s.reviewer());
        jdbc.update("UPDATE notification_delivery SET created_at = now() - interval '40 days' WHERE user_id = ? AND state = 'SENT'", s.reviewer());

        delivery.purgeOld();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE user_id = ? AND state = 'SENT'", Integer.class, s.reviewer())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE id = ?", Integer.class, pending)).as("pending is kept").isEqualTo(1);
    }

    @Test
    void VYB0955_AC20_aRolledBackChangeQueuesNothing() {
        Scene s = scene();
        int before = emailTitles(s.reviewer()).size();
        Requirement r = newRequirement(s.place(), s.author());
        UUID outsider = newUser("outsider");
        try {
            move(r, RequirementStatus.IN_REVIEW, "x", outsider); // only the author may submit
        } catch (RuntimeException expected) {
            // refused
        }
        assertThat(emailTitles(s.reviewer())).hasSize(before);
        assertThat(inboxTotal(s.reviewer())).isZero();
    }
}
