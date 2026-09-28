package com.vyoog.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    @Test
    void sameSecretAndBodyVerifies() {
        String sig = WebhookSignatureVerifier.sign("s3cret", "{\"a\":1}");
        assertThat(WebhookSignatureVerifier.verify("s3cret", "{\"a\":1}", sig)).isTrue();
    }

    @Test
    void wrongSecretDoesNotVerify() {
        String sig = WebhookSignatureVerifier.sign("s3cret", "{\"a\":1}");
        assertThat(WebhookSignatureVerifier.verify("other", "{\"a\":1}", sig)).isFalse();
    }

    @Test
    void tamperedBodyDoesNotVerify() {
        String sig = WebhookSignatureVerifier.sign("s3cret", "{\"a\":1}");
        assertThat(WebhookSignatureVerifier.verify("s3cret", "{\"a\":2}", sig)).isFalse();
    }

    @Test
    void missingSignatureDoesNotVerify() {
        assertThat(WebhookSignatureVerifier.verify("s3cret", "{\"a\":1}", null)).isFalse();
        assertThat(WebhookSignatureVerifier.verify("s3cret", "{\"a\":1}", "")).isFalse();
    }

    @Test
    void deterministic() {
        String a = WebhookSignatureVerifier.sign("s3cret", "same body");
        String b = WebhookSignatureVerifier.sign("s3cret", "same body");
        assertThat(a).isEqualTo(b);
    }
}
