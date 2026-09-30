package com.vyoog.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * VYB-0900 (F03): CORS is credentialed, so the allowlist must be explicit. No wildcard
 * default, no wildcard accepted, and an unlisted origin is never reflected back.
 */
class SecurityConfigCorsAllowlistTest {

    private static SecurityConfig configWith(String origins, String patterns) {
        SecurityConfig config = new SecurityConfig(null);
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", patterns);
        return config;
    }

    private static MockHttpServletResponse preflight(SecurityConfig config, String origin) throws Exception {
        CorsConfiguration cors = config.corsConfigurationSource()
            .getCorsConfiguration(new MockHttpServletRequest("POST", "/api/v1/requirements"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/requirements");
        req.addHeader("Origin", origin);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new DefaultCorsProcessor().processRequest(cors, req, res);
        return res;
    }

    @Test
    void VYB0900_AC4_anUnlistedOriginIsNotReflectedWithCredentials() throws Exception {
        SecurityConfig config = configWith("https://requirements.evyoog.com", "");
        MockHttpServletResponse res = preflight(config, "https://evil.example");
        assertThat(res.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void VYB0900_AC4_aListedOriginIsAllowedWithCredentials() throws Exception {
        SecurityConfig config = configWith("https://requirements.evyoog.com", "");
        MockHttpServletResponse res = preflight(config, "https://requirements.evyoog.com");
        assertThat(res.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://requirements.evyoog.com");
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void VYB0900_AC4_withNoPatternsConfiguredALocalhostOriginIsNotAllowed() throws Exception {
        // The old default pattern was '*'. Empty now, so nothing beyond the exact list matches.
        SecurityConfig config = configWith("https://requirements.evyoog.com", "");
        assertThat(preflight(config, "http://localhost:5173").getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void VYB0900_AC4_theSourceDefaultForPatternsIsEmptyNotAWildcard() throws Exception {
        var field = SecurityConfig.class.getDeclaredField("allowedOriginPatterns");
        var value = field.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        assertThat(value).isEqualTo("${vyoog.cors-allowed-origin-patterns:}");
    }

    @Test
    void VYB0900_AC4_aBareWildcardInEitherListIsRefusedAtStartup() {
        assertThatThrownBy(() -> configWith("*", "").corsConfigurationSource())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vyoog.cors-allowed-origins");
        assertThatThrownBy(() -> configWith("https://requirements.evyoog.com", "*").corsConfigurationSource())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vyoog.cors-allowed-origin-patterns");
        assertThatThrownBy(() -> configWith("", "https://*").corsConfigurationSource())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configWith("", "http://*:*").corsConfigurationSource())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void VYB0900_AC4_hostScopedPatternsAreStillAccepted() {
        configWith("https://requirements.evyoog.com", "http://localhost:*,http://192.168.*.*:*")
            .corsConfigurationSource();
    }
}
