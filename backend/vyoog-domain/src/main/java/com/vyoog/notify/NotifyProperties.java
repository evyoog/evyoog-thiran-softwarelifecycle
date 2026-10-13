package com.vyoog.notify;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0955 (D47, rule 9): how email and chat are reached. Every value comes from the environment and the defaults are empty, which
 * switches the channel off: nothing is queued for it and nobody is told it exists beyond the settings screen saying "not set up". The
 * mail password and the chat webhook address are secrets; neither is ever logged or returned by an endpoint.
 *
 * <p>A half-set channel is a mistake and stops the application starting with a message saying which value is missing, as a missing
 * required secret does (D22), rather than silently sending nothing.
 */
@Component
public class NotifyProperties {

    private final String mailHost;
    private final int mailPort;
    private final String mailUsername;
    private final String mailPassword;
    private final String mailFrom;
    private final boolean mailStartTls;
    private final String chatWebhookUrl;
    private final String publicBaseUrl;

    public NotifyProperties(
            @Value("${vyoog.notify.email.host:}") String mailHost,
            @Value("${vyoog.notify.email.port:587}") int mailPort,
            @Value("${vyoog.notify.email.username:}") String mailUsername,
            @Value("${vyoog.notify.email.password:}") String mailPassword,
            @Value("${vyoog.notify.email.from:}") String mailFrom,
            @Value("${vyoog.notify.email.starttls:true}") boolean mailStartTls,
            @Value("${vyoog.notify.chat.teams-webhook-url:}") String chatWebhookUrl,
            @Value("${vyoog.notify.public-base-url:}") String publicBaseUrl) {
        this.mailHost = blankToEmpty(mailHost);
        this.mailPort = mailPort;
        this.mailUsername = blankToEmpty(mailUsername);
        this.mailPassword = blankToEmpty(mailPassword);
        this.mailFrom = blankToEmpty(mailFrom);
        this.mailStartTls = mailStartTls;
        this.chatWebhookUrl = blankToEmpty(chatWebhookUrl);
        this.publicBaseUrl = stripTrailingSlash(blankToEmpty(publicBaseUrl));
        validate();
    }

    private void validate() {
        if (!mailHost.isEmpty() && mailFrom.isEmpty()) {
            throw new IllegalStateException("MAIL_HOST is set but MAIL_FROM is not: email needs a sender address, or leave both empty to switch email off");
        }
        if (mailHost.isEmpty() && !mailFrom.isEmpty()) {
            throw new IllegalStateException("MAIL_FROM is set but MAIL_HOST is not: email needs a server, or leave both empty to switch email off");
        }
        if (!mailUsername.isEmpty() && mailPassword.isEmpty()) {
            throw new IllegalStateException("MAIL_USERNAME is set but MAIL_PASSWORD is not");
        }
        if (!chatWebhookUrl.isEmpty()) {
            URI uri;
            try {
                uri = URI.create(chatWebhookUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("NOTIFY_TEAMS_WEBHOOK_URL is not a valid address");
            }
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost());
            if (uri.getHost() == null || !(https || localHttp)) {
                throw new IllegalStateException("NOTIFY_TEAMS_WEBHOOK_URL must be an https address (plain http is accepted only for this machine, for testing)");
            }
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    public boolean emailEnabled() { return !mailHost.isEmpty(); }
    public boolean chatEnabled() { return !chatWebhookUrl.isEmpty(); }

    public String mailHost() { return mailHost; }
    public int mailPort() { return mailPort; }
    public String mailUsername() { return mailUsername; }
    public String mailPassword() { return mailPassword; }
    public String mailFrom() { return mailFrom; }
    public boolean mailStartTls() { return mailStartTls; }
    public String chatWebhookUrl() { return chatWebhookUrl; }
    /** The address people reach the app at, with no trailing slash; empty when not set (messages then carry no link). */
    public String publicBaseUrl() { return publicBaseUrl; }

    /** A link in a message: the public address plus the in-app path, or nothing when either is missing. */
    public String absoluteLink(String path) {
        if (publicBaseUrl.isEmpty() || path == null || path.isBlank() || !path.startsWith("/")) return null;
        return publicBaseUrl + path;
    }

    private static String blankToEmpty(String s) { return s == null ? "" : s.strip(); }

    private static String stripTrailingSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
