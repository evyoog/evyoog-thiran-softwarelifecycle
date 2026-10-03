package com.vyoog.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** VYB-0913 (F40): nothing that reaches a header or a URL can inject a header line or leave the host. */
class ConnectorOperationTest {

    private static ConnectorOperation op(String key, String path) {
        return new ConnectorOperation("planning", "brief.push", key, "POST", path, "application/json", new byte[0]);
    }

    @Test
    void VYB0913_AC4_anIdempotencyKeyWithALineBreakCannotInjectAHeader() {
        assertThatThrownBy(() -> op("abc\r\nX-Evil: 1", "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> op("has space", "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> op("", "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> op("k".repeat(201), "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(op("brief:3f2a-9c:rev=7", "/x").idempotencyKey()).isEqualTo("brief:3f2a-9c:rev=7");
    }

    @Test
    void VYB0913_AC4_aPathCannotPointTheRequestAtAnotherHost() {
        assertThatThrownBy(() -> op("k", "//evil.example.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> op("k", "http://evil.example.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> op("k", "@evil.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> op("k", "/x\r\nHost: evil")).isInstanceOf(IllegalArgumentException.class);
        assertThat(op("k", "").path()).isEmpty();
        assertThat(op("k", "/api/v1/functions/42").path()).isEqualTo("/api/v1/functions/42");
    }

    @Test
    void VYB0913_AC4_methodAndContentTypeAreCheckedAndDefaultSensibly() {
        ConnectorOperation defaults = new ConnectorOperation("planning", "x.y", "k", null, null, null, null);
        assertThat(defaults.method()).isEqualTo("POST");
        assertThat(defaults.contentType()).isEqualTo("application/json");
        assertThat(defaults.body()).isEmpty();
        assertThatThrownBy(() -> new ConnectorOperation("planning", "x.y", "k", "TRACE", "", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorOperation("planning", "x.y", "k", "POST", "", "text/plain\r\nX: y", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ConnectorOperation("planning", "x.y", "k", "POST", "", "multipart/form-data; boundary=----VyoogBoundary1a-2b", null)
            .contentType()).startsWith("multipart/form-data");
    }
}
