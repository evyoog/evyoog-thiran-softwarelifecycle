package com.vyoog.integration;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * VYB-0741 AC1: HMAC-SHA256 over the raw request body, hex-encoded, compared in
 * constant time — the same scheme GitHub/Stripe-style webhooks use, so a real
 * external system's signature header is a straight drop-in once one exists to test
 * against (see {@link WebhookService}'s own disclosed caveat: never exercised
 * against a real sender in this environment).
 */
public final class WebhookSignatureVerifier {

    private WebhookSignatureVerifier() {}

    public static String sign(String secret, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verify(String secret, String rawBody, String presentedSignature) {
        if (presentedSignature == null || presentedSignature.isBlank()) return false;
        String expected = sign(secret, rawBody);
        return java.security.MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), presentedSignature.getBytes(StandardCharsets.UTF_8));
    }
}
