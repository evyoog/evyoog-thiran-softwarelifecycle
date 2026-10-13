package com.vyoog.api.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.notify.NotificationChannel;
import com.vyoog.notify.NotificationDeliveryQueue;
import com.vyoog.notify.NotificationDeliveryQueue.Delivery;
import com.vyoog.notify.NotifyProperties;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * VYB-0955 (D47): sends what the notification queue holds, by email and to the team chat channel. Run every 30 seconds by
 * {@code ScheduledJobs} behind a lock, so with several instances one of them sends. Each message is tried on its own: one that fails
 * is recorded as failed (tried again after 1, 2, 4 and 8 minutes, then left failed and shown on the notification settings) and never
 * holds up the next, and nothing here can fail the change that caused a message, because the message was only queued with it.
 *
 * <p>Email: an urgent message goes on its own; a person's non-urgent ones go together as one digest. Chat: one post per queued row, as
 * an Adaptive Card to a Teams (Workflows) webhook. A message carries the same words the inbox shows and, when {@code PUBLIC_BASE_URL}
 * is set, a link. The webhook address and the mail password never appear in a log, an error stored on a row, or any response.
 *
 * <p>Not tested against the real mail server or a real Teams channel (there is neither here): see {@code NotificationDeliveryIT}, which
 * uses a local fake of each.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int BATCH = 50;
    private static final Duration KEEP_SENT_AND_FAILED = Duration.ofDays(30);

    private final NotificationDeliveryQueue queue;
    private final NotifyProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile JavaMailSenderImpl mail;

    public NotificationDeliveryService(NotificationDeliveryQueue queue, NotifyProperties properties, ObjectMapper json) {
        this.queue = queue;
        this.properties = properties;
        this.json = json;
    }

    /** What one pass did. */
    public record Result(int emailsSent, int chatPostsSent, int failed) {}

    /** The outcome of a test message to the channel itself. */
    public record TestResult(boolean ok, String detail) {}

    /** One pass at a time in this process (the scheduled job and an administrator's test never both pick up the same row). */
    public synchronized Result deliver() {
        int email = 0;
        int chat = 0;
        int failed = 0;
        if (properties.emailEnabled()) {
            for (Delivery d : queue.dueUrgentEmail(BATCH)) {
                String error = trySend(() -> sendEmail(d.email(), subjectOf(d.title()), bodyOf(List.of(d))));
                if (error == null) { queue.markSent(List.of(d.id())); email++; } else { queue.markFailed(List.of(d.id()), error); failed++; }
            }
            for (UUID user : queue.usersWithDueDigest(BATCH)) {
                List<Delivery> rows = queue.digestOf(user);
                if (rows.isEmpty()) continue;
                List<UUID> ids = rows.stream().map(Delivery::id).toList();
                String subject = rows.size() == 1 ? subjectOf(rows.get(0).title()) : "[Vyoog] " + rows.size() + " updates for you";
                String error = trySend(() -> sendEmail(rows.get(0).email(), subject, bodyOf(rows)));
                if (error == null) { queue.markSent(ids); email++; } else { queue.markFailed(ids, error); failed++; }
            }
        } else {
            queue.failStale(NotificationChannel.EMAIL, "email is not set up on this deployment");
        }
        if (properties.chatEnabled()) {
            for (Delivery d : queue.dueChat(BATCH)) {
                String error = trySend(() -> postToChat(d.title(), d.subtitle(), d.link()));
                if (error == null) { queue.markSent(List.of(d.id())); chat++; } else { queue.markFailed(List.of(d.id()), error); failed++; }
            }
        } else {
            queue.failStale(NotificationChannel.CHAT, "chat is not set up on this deployment");
        }
        return new Result(email, chat, failed);
    }

    /** Nightly: sent and failed rows older than 30 days go. Pending ones are never purged. */
    public int purgeOld() {
        return queue.purgeOlderThan(Instant.now().minus(KEEP_SENT_AND_FAILED));
    }

    /** Sends one message straight to a channel so an administrator can see it work (email goes to {@code toEmail}). */
    public TestResult sendTest(NotificationChannel channel, String toEmail) {
        if (channel == NotificationChannel.EMAIL) {
            if (!properties.emailEnabled()) return new TestResult(false, "Email is not set up on this deployment.");
            String error = trySend(() -> sendEmail(toEmail, "[Vyoog] Test message",
                "This is a test message from Vyoog.\n\nIf you can read it, email is set up correctly.\n"));
            return error == null ? new TestResult(true, "Sent to " + toEmail + ".") : new TestResult(false, error);
        }
        if (channel == NotificationChannel.CHAT) {
            if (!properties.chatEnabled()) return new TestResult(false, "Chat is not set up on this deployment.");
            String error = trySend(() -> postToChat("Vyoog test message", "If you can read this, the team channel is set up correctly.", null));
            return error == null ? new TestResult(true, "Posted to the team channel.") : new TestResult(false, error);
        }
        return new TestResult(false, "Only EMAIL and CHAT can be tested.");
    }

    // ---------------------------------------------------------------- email

    private interface Send { void run() throws Exception; }

    /** @return null on success, else a short description of what went wrong with secrets removed */
    private String trySend(Send send) {
        try {
            send.run();
            return null;
        } catch (Exception e) {
            String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            String clean = scrub(text);
            log.warn("[notify] a message could not be sent: {}", clean);
            return clean;
        }
    }

    String scrub(String text) {
        String out = text.replace('\n', ' ').replace('\r', ' ');
        for (String secret : new String[] {properties.mailPassword(), properties.chatWebhookUrl()}) {
            if (secret != null && !secret.isEmpty()) out = out.replace(secret, "[removed]");
        }
        // The webhook's path and query carry its signature: if the address leaks in another form, drop everything after the host.
        return out.replaceAll("(https?://[^/\\s]+)[^\\s]*", "$1/[removed]");
    }

    private JavaMailSenderImpl sender() {
        JavaMailSenderImpl m = mail;
        if (m == null) {
            m = new JavaMailSenderImpl();
            m.setHost(properties.mailHost());
            m.setPort(properties.mailPort());
            m.setDefaultEncoding("UTF-8");
            java.util.Properties p = m.getJavaMailProperties();
            p.put("mail.smtp.connectiontimeout", "10000");
            p.put("mail.smtp.timeout", "15000");
            p.put("mail.smtp.writetimeout", "15000");
            p.put("mail.smtp.starttls.enable", String.valueOf(properties.mailStartTls()));
            if (!properties.mailUsername().isEmpty()) {
                m.setUsername(properties.mailUsername());
                m.setPassword(properties.mailPassword());
                p.put("mail.smtp.auth", "true");
                // never send the password over a connection that has not been upgraded to TLS
                if (properties.mailStartTls()) p.put("mail.smtp.starttls.required", "true");
            }
            mail = m;
        }
        return m;
    }

    private void sendEmail(String to, String subject, String body) throws Exception {
        if (to == null || to.isBlank()) throw new IllegalStateException("the recipient has no email address");
        JavaMailSenderImpl m = sender();
        MimeMessage message = m.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(properties.mailFrom());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        m.send(message);
    }

    static String subjectOf(String title) {
        String clean = title == null ? "" : title.replaceAll("[\\r\\n]+", " ").strip();
        if (clean.length() > 150) clean = clean.substring(0, 150) + "...";
        return "[Vyoog] " + clean;
    }

    String bodyOf(List<Delivery> rows) {
        StringBuilder b = new StringBuilder();
        for (Delivery d : rows) {
            b.append(d.title()).append('\n');
            if (d.subtitle() != null && !d.subtitle().isBlank()) b.append(d.subtitle()).append('\n');
            String link = properties.absoluteLink(d.link());
            if (link != null) b.append(link).append('\n');
            b.append('\n');
        }
        b.append("-- \nYou are getting this because of your notification choices in Vyoog.\n")
            .append("Change them from the bell at the top of the page, under Preferences.\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- chat

    private void postToChat(String title, String subtitle, String link) throws Exception {
        List<Map<String, Object>> body = new ArrayList<>();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("type", "TextBlock");
        head.put("text", title);
        head.put("weight", "Bolder");
        head.put("wrap", true);
        body.add(head);
        if (subtitle != null && !subtitle.isBlank()) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("type", "TextBlock");
            sub.put("text", subtitle);
            sub.put("wrap", true);
            sub.put("isSubtle", true);
            body.add(sub);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", body);
        String absolute = properties.absoluteLink(link);
        if (absolute != null) {
            card.put("actions", List.of(Map.of("type", "Action.OpenUrl", "title", "Open in Vyoog", "url", absolute)));
        }
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("content", card);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("attachments", List.of(attachment));

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.chatWebhookUrl()))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String detail = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
            if (detail.length() > 200) detail = detail.substring(0, 200);
            throw new IllegalStateException("the channel answered HTTP " + response.statusCode() + (detail.isEmpty() ? "" : " " + detail));
        }
    }
}
