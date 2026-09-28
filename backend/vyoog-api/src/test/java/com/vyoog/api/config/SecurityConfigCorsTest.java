package com.vyoog.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

/**
 * VYB-0834/0835: reaching the Vite dev server from another device on the same LAN
 * (it's bound to every interface via {@code vite --host}) used to 403 at the CORS
 * layer, since {@code vyoog.cors-allowed-origins} is an exact-match list that can never
 * anticipate whichever address or port the browser actually used — Vite's own dev port
 * isn't fixed either (it free-ports upward if 5173 is taken). Fixed with origin
 * PATTERNS for localhost/loopback and the RFC 1918 private ranges, `*` standing in for
 * both the host and the port — not a literal IP or port, since the whole point is this
 * has to work for any machine, on any private network, on whatever port it's running,
 * not one specific address. The production origin is untouched — still exact-matched,
 * never a pattern — proven here alongside the new patterns so a future edit can't
 * accidentally widen it.
 */
class SecurityConfigCorsTest {

    private CorsConfiguration corsConfigFor(String path) {
        SecurityConfig config = new SecurityConfig(null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://requirements.evyoog.com");
        ReflectionTestUtils.setField(config, "allowedOriginPatterns",
            "http://localhost:*,http://127.0.0.1:*,http://10.*.*.*:*,http://172.16.*.*:*,http://172.31.*.*:*,http://192.168.*.*:*");
        return config.corsConfigurationSource().getCorsConfiguration(new MockHttpServletRequest("POST", path));
    }

    private CorsConfiguration cors() {
        return corsConfigFor("/api/v1/auth/login");
    }

    @Test
    void VYB0835_AC1_aLanOriginOnAnyPortIsAllowed() {
        CorsConfiguration cors = cors();
        assertThat(cors.checkOrigin("http://192.168.1.4:5173")).isEqualTo("http://192.168.1.4:5173");
        assertThat(cors.checkOrigin("http://192.168.1.4:4173")).isEqualTo("http://192.168.1.4:4173");
        assertThat(cors.checkOrigin("http://192.168.1.4:3000")).isEqualTo("http://192.168.1.4:3000");
    }

    @Test
    void VYB0835_AC1_everyRfc1918RangeOnAnyPortIsAllowed() {
        CorsConfiguration cors = cors();
        assertThat(cors.checkOrigin("http://10.20.30.40:8123")).isEqualTo("http://10.20.30.40:8123");
        assertThat(cors.checkOrigin("http://172.16.0.1:9000")).isEqualTo("http://172.16.0.1:9000");
        assertThat(cors.checkOrigin("http://172.31.255.254:5555")).isEqualTo("http://172.31.255.254:5555");
    }

    @Test
    void VYB0835_AC1_localhostAndLoopbackOnAnyPortAreAllowed() {
        CorsConfiguration cors = cors();
        assertThat(cors.checkOrigin("http://localhost:5174")).isEqualTo("http://localhost:5174");
        assertThat(cors.checkOrigin("http://127.0.0.1:8081")).isEqualTo("http://127.0.0.1:8081");
    }

    @Test
    void VYB0835_AC2_theProductionOriginStillMatchesExactlyAndIsUnwidened() {
        assertThat(cors().checkOrigin("https://requirements.evyoog.com")).isEqualTo("https://requirements.evyoog.com");
    }

    @Test
    void VYB0835_AC2_aPublicIpIsNotAllowedOnAnyPort() {
        CorsConfiguration cors = cors();
        assertThat(cors.checkOrigin("http://8.8.8.8:5173")).isNull();
        assertThat(cors.checkOrigin("http://93.184.216.34:3000")).isNull();
    }

    @Test
    void VYB0835_AC2_httpsOnALanIpIsNotAllowed() {
        // The patterns only ever widen http — never https, which nothing on a LAN dev
        // box actually serves.
        assertThat(cors().checkOrigin("https://192.168.1.4:5173")).isNull();
    }
}
