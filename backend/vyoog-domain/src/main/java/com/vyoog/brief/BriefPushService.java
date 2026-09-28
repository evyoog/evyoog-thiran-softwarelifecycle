package com.vyoog.brief;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.WebhookSignatureVerifier;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.portfolio.Product;
import com.vyoog.portfolio.ProductRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0818/0837: a generated brief, pushed out to whatever the "planning" {@link
 * IntegrationConnection} (seeded OUTBOUND, V007, {@code owns = "delivery-tool push"})
 * is configured with — the same connection and the same real HTTP-POST-plus-signature
 * mechanism {@link com.vyoog.signals.SignalsExportService#pushToDeliveryTool} already
 * uses to push scope signals out to a planning/delivery tool. Deliberately its own
 * class rather than a method added there: that service is named and scoped around
 * signals specifically, and a brief's payload (a whole markdown document, not eight
 * numbers) is a different shape with different callers.
 *
 * <p>The destination itself is not this decision's to make — {@code pushUrl}/{@code
 * webhookSecret} on the "planning" connection are configured in Administration by
 * whoever knows what that other tool actually is. Until they are, this refuses with a
 * named reason (Principle 8: "not connected", never a silent no-op) rather than
 * pretending to succeed.
 *
 * <p>VYB-0837: sent as a real {@code multipart/form-data} upload, not a JSON envelope —
 * the receiving planning tool has its own file-upload capability, and the brief's
 * markdown rides as an actual file part (not a string field) so it lands there the same
 * way any other file upload to that tool would. Alongside it: the product/app/capability
 * names the brief covers, and whether it was generated app-wide or narrowed to specific
 * capabilities ({@code level}) — read from {@code brief_capability}, the exact selection
 * recorded at generation time, not reconstructed afterward from which requirements
 * happened to end up in the brief.
 *
 * <p>VYB-0837 (follow-on): the receiving tool's own endpoint additionally expects {@code
 * projectName} and {@code customerName} — concepts Vyoog itself has no equivalent of (no
 * "project" entity, no "customer" concept per CLAUDE.md rule 1, single-tenant). Per
 * product-owner instruction: {@code projectName} is the most specific name this brief
 * covers — the capability name if it's capability-scoped, else the application name
 * (there is currently no way for a brief to be narrower than an app but have no app, since
 * {@code Brief.applicationId} is never null — the product-name fallback below exists for
 * that case anyway, defensively, exactly as instructed). {@code customerName} is read the
 * same optional-config way as {@code apiKey}, defaulting to the literal {@code "vyoog"} —
 * config-driven rather than a bare constant so setting a real one later needs no code
 * change, matching what was actually asked for ("for now vyoog, later the responsive
 * customer name").
 */
@Service
public class BriefPushService {

    private final BriefRepository briefs;
    private final ApplicationRepository applications;
    private final ProductRepository products;
    private final CapabilityRepository capabilities;
    private final IntegrationService integrations;
    private final AuditService audit;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final String CONNECTION_KEY = "planning";

    public BriefPushService(BriefRepository briefs, ApplicationRepository applications, ProductRepository products,
                             CapabilityRepository capabilities, IntegrationService integrations, AuditService audit,
                             ObjectMapper json, JdbcTemplate jdbc) {
        this.briefs = briefs;
        this.applications = applications;
        this.products = products;
        this.capabilities = capabilities;
        this.integrations = integrations;
        this.audit = audit;
        this.json = json;
        this.jdbc = jdbc;
    }

    public record PushResult(boolean success, int statusCode, String error) {}

    @Transactional
    public PushResult push(UUID briefId, UUID actor) {
        Brief brief = briefs.findById(briefId).orElseThrow(NoSuchElementException::new);
        IntegrationConnection conn = integrations.get(CONNECTION_KEY);
        String pushUrl = readConfigField(conn.getConfig(), "pushUrl");
        if (pushUrl == null || pushUrl.isBlank()) {
            throw new IllegalStateException(
                "No push URL configured for \"" + CONNECTION_KEY + "\" — set one in Administration first.");
        }
        String secret = conn.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "No shared secret configured for \"" + CONNECTION_KEY + "\" — the receiver couldn't verify this push anyway.");
        }
        String apiKey = readConfigField(conn.getConfig(), "apiKey");
        String customerName = readConfigField(conn.getConfig(), "customerName");
        if (customerName == null || customerName.isBlank()) {
            customerName = "vyoog";
        }

        Application application = applications.findById(brief.getApplicationId())
            .orElseThrow(() -> new NoSuchElementException("No such application"));
        Product product = products.findById(application.getProductId())
            .orElseThrow(() -> new NoSuchElementException("No such product"));

        // VYB-0837: exactly what was selected when this brief was generated
        // (brief_capability) — empty means it was generated app-wide, not narrowed.
        List<UUID> scopedCapabilityIds = jdbc.queryForList(
            "SELECT capability_id FROM brief_capability WHERE brief_id = ?", UUID.class, brief.getId());
        String level = scopedCapabilityIds.isEmpty() ? "APPLICATION" : "CAPABILITY";
        String capabilityName = scopedCapabilityIds.isEmpty() ? ""
            : capabilities.findAllById(scopedCapabilityIds).stream().map(Capability::getName)
                .collect(Collectors.joining(", "));

        // VYB-0837: the most specific name this brief covers — capability, else app,
        // else product. Reuses the same capabilityName already computed above rather
        // than a separate per-requirement placement-level lookup (the product owner
        // explicitly chose the brief's own scope, not per-requirement placement, when
        // this was first clarified).
        String projectName = !capabilityName.isBlank() ? capabilityName
            : (application.getName() != null && !application.getName().isBlank() ? application.getName()
            : product.getName());

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("projectName", projectName);
        fields.put("customerName", customerName);
        fields.put("productName", product.getName());
        fields.put("appName", application.getName());
        fields.put("capabilityName", capabilityName);
        fields.put("level", level);

        String filename = "VY-" + slug(application.getName()) + "-implementation-brief.md";
        String boundary = "----VyoogBoundary" + UUID.randomUUID();
        String body = buildMultipartBody(boundary, fields, "file", filename, "text/markdown", brief.getContent());
        String signature = WebhookSignatureVerifier.sign(secret, body);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(pushUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-Vyoog-Signature", signature);
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        PushResult result;
        try {
            HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            result = new PushResult(ok, response.statusCode(), ok ? null : "HTTP " + response.statusCode() + ": " + response.body());
        } catch (IOException | InterruptedException e) {
            result = new PushResult(false, 0, e.getMessage());
        }

        if (result.success()) {
            integrations.setConnected(CONNECTION_KEY, true, null);
        } else {
            integrations.recordFailure(CONNECTION_KEY, result.error());
        }
        audit.record(actor, "brief.pushed", "INTEGRATION_CONNECTION", brief.getId(), null,
            Map.of("connection", CONNECTION_KEY, "success", result.success(), "statusCode", result.statusCode()));
        return result;
    }

    /**
     * JDK's {@code HttpClient} has no built-in multipart/form-data support, so this is
     * hand-rolled. Every part here is text — the form fields and the markdown file
     * itself — so the whole body is safely built as one {@code String} rather than raw
     * bytes, which keeps this compatible with {@link WebhookSignatureVerifier#sign}'s
     * String-based signing without a second, byte-array signing path.
     */
    private static String buildMultipartBody(String boundary, Map<String, String> fields, String fileFieldName,
                                              String filename, String fileContentType, String fileContent) {
        StringBuilder sb = new StringBuilder();
        for (var entry : fields.entrySet()) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n");
            sb.append(entry.getValue() == null ? "" : entry.getValue()).append("\r\n");
        }
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(fileFieldName)
          .append("\"; filename=\"").append(filename).append("\"\r\n");
        sb.append("Content-Type: ").append(fileContentType).append("\r\n\r\n");
        sb.append(fileContent).append("\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    private static String slug(String s) {
        String lower = s == null ? "" : s.toLowerCase();
        return lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String readConfigField(String configJson, String field) {
        if (configJson == null) return null;
        try {
            var node = json.readTree(configJson);
            var value = node.get(field);
            return value == null ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
