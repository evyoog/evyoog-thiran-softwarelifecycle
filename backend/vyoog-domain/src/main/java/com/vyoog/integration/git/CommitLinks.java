package com.vyoog.integration.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * VYB-0922 (D53): where a commit lives. The {@code git} connection's configuration holds {@code repoUrl}; a commit's page is
 * {@code <repoUrl>/commit/<sha>}, which is where GitHub, Gitea and GitLab (which redirects it) keep it. Bitbucket and Azure DevOps use other
 * paths and are not covered: for them no link is made rather than a wrong one.
 *
 * <p>The address becomes a link a person clicks, so it is checked when it is saved ({@code IntegrationService.setConfig}) and again here:
 * https only, a host, no credentials, query or fragment. A trailing slash and a trailing {@code .git} are dropped. Anything else (no address
 * set, an unusable one, a sha that is not hexadecimal) gives no link and never an error.
 */
@Service
public class CommitLinks {

    public static final String CONNECTION_KEY = "git";
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,64}");
    private static final Pattern PATH_OK = Pattern.compile("(/[A-Za-z0-9._~%!$&'()*+,;=:@-]+)*");

    private final IntegrationService integrations;
    private final ObjectMapper json;

    public CommitLinks(IntegrationService integrations, ObjectMapper json) {
        this.integrations = integrations;
        this.json = json;
    }

    /** The page of this commit in the configured repository, or empty when there is none to make. */
    public Optional<String> urlFor(String sha) {
        Optional<String> base = baseUrl();
        return base.flatMap(b -> urlFor(b, sha));
    }

    /** The configured repository address, normalised, or empty if none is set or it is not usable. */
    public Optional<String> baseUrl() {
        try {
            IntegrationConnection conn = integrations.get(CONNECTION_KEY);
            return repoUrlOf(conn.getConfig(), json).flatMap(CommitLinks::normalise);
        } catch (NoSuchElementException notRegistered) {
            return Optional.empty();
        }
    }

    public static Optional<String> urlFor(String baseUrl, String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) return Optional.empty();
        return Optional.of(baseUrl + "/commit/" + sha.toLowerCase());
    }

    /** The first seven characters, the way git shows a commit. */
    public static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
    }

    /** The first line of a commit message, cut to a length a list can show. */
    public static String subjectOf(String message) {
        if (message == null) return "";
        String first = message.strip().lines().findFirst().orElse("").strip();
        return first.length() > 100 ? first.substring(0, 100) + "…" : first;
    }

    // ------------------------------------------------------------------ the address

    private static Optional<String> repoUrlOf(String configJson, ObjectMapper json) {
        if (configJson == null || configJson.isBlank()) return Optional.empty();
        try {
            JsonNode node = json.readTree(configJson);
            JsonNode url = node == null ? null : node.get("repoUrl");
            return url == null || url.isNull() || url.asText().isBlank() ? Optional.empty() : Optional.of(url.asText().strip());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Why this address cannot be linked to, or empty if it can. */
    static Optional<String> problem(String repoUrl) {
        URI uri;
        try {
            uri = new URI(repoUrl);
        } catch (java.net.URISyntaxException e) {
            return Optional.of("repoUrl is not a valid address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https")) return Optional.of("repoUrl must start with https://");
        if (uri.getHost() == null || uri.getHost().isBlank()) return Optional.of("repoUrl has no host");
        if (uri.getUserInfo() != null) return Optional.of("repoUrl must not contain a user name or password");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return Optional.of("repoUrl must not have a query or a fragment");
        String path = stripSuffix(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (path.length() > 200 || !PATH_OK.matcher(path).matches()) return Optional.of("repoUrl has a path that cannot be used in a link");
        return Optional.empty();
    }

    /** The address as a base for commit links: no trailing slash, no trailing {@code .git}. Empty if it cannot be one. */
    static Optional<String> normalise(String repoUrl) {
        if (problem(repoUrl).isPresent()) return Optional.empty();
        URI uri = URI.create(repoUrl);
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return Optional.of("https://" + uri.getHost().toLowerCase() + port + stripSuffix(uri.getRawPath() == null ? "" : uri.getRawPath()));
    }

    private static String stripSuffix(String path) {
        String p = path.replaceAll("/+$", "");
        if (p.endsWith(".git")) p = p.substring(0, p.length() - 4);
        return p.replaceAll("/+$", "");
    }

    /**
     * For {@code IntegrationService.setConfig}: the problem with a {@code git} configuration, or empty. A configuration with no
     * {@code repoUrl} is allowed (it simply makes no links); one that is not JSON is the caller's problem and is left to be stored as before.
     */
    public static Optional<String> problemWith(String configJson) {
        return repoUrlOf(configJson, new ObjectMapper()).flatMap(CommitLinks::problem);
    }
}
