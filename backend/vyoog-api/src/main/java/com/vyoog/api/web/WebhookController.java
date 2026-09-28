package com.vyoog.api.web;

import com.vyoog.integration.IntegrationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0741: signature-verified, replay-protected inbound webhooks. {@code
 * SecurityConfig} carves {@code /api/v1/webhooks/**} out of {@code
 * .anyRequest().authenticated()} — a real sender has no eVyoog bearer token at all,
 * it authenticates by HMAC signature, verified below instead. Never exercised against
 * a real external sender in this environment (see BUILD-REGISTER.md); the signature
 * scheme and replay protection are unit-tested directly.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final IntegrationService integrations;

    public WebhookController(IntegrationService integrations) {
        this.integrations = integrations;
    }

    @PostMapping("/{key}")
    public ResponseEntityShim receive(
            @PathVariable String key,
            @RequestHeader("X-Signature") String signature,
            @RequestHeader("X-Delivery-Id") String deliveryId,
            @RequestBody String rawBody) {
        boolean processed = integrations.acceptDelivery(key, deliveryId, rawBody, signature);
        // VYB-0741 AC2: a replay is 200 either way — "ignored idempotently" is not an error response.
        return new ResponseEntityShim(processed);
    }

    /** A tiny record instead of {@code ResponseEntity<Map>} so the JSON shape is self-explanatory. */
    public record ResponseEntityShim(boolean processed) {}

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> onMissingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
        return Map.of("detail", "An unsigned or unidentified delivery is refused: " + e.getMessage());
    }
}
