package com.vyoog.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;

/**
 * Decodes (never verifies) the payload of a JWT we just received directly from
 * Keycloak's own token endpoint over TLS — signature verification is Keycloak's
 * job at issuance time, not ours to redo here. Never use this on a token that
 * arrived from anywhere else (e.g. an incoming request's Authorization header) —
 * that path goes through Spring's real, signature-verifying JWT decoder instead
 * (see SecurityConfig's jwtDecoder()).
 */
public final class JwtPayloadUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtPayloadUtil() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodePayload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Not a JWT");
        }
        byte[] json = Base64.getUrlDecoder().decode(parts[1]);
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JWT payload", e);
        }
    }
}
