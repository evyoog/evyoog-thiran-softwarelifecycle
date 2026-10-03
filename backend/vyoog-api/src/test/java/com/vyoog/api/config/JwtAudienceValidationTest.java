package com.vyoog.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * VYB-0908 (F09): a token is accepted only if it was issued for this API. These use really signed
 * tokens and a real decoder, so signature, issuer, expiry and audience are all checked together
 * exactly as {@link SecurityConfig#jwtDecoder()} does, minus the network fetch of the key set.
 */
class JwtAudienceValidationTest {

    private static final String ISSUER = "https://user.example.test/realms/eVyoog";
    private static final String AUDIENCE = "vyoog-api";

    private final KeyPair keys = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JwtDecoder decoder(String configuredAudience) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        d.setJwtValidator(SecurityConfig.tokenValidator(ISSUER, configuredAudience));
        return d;
    }

    private String token(String issuer, List<String> audience, Instant expires, KeyPair signWith) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject("sub-1").issuer(issuer)
            .issueTime(new Date()).expirationTime(Date.from(expires)).claim("azp", "vyoog-web");
        if (audience != null) claims.audience(audience);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(signWith.getPrivate()));
        return jwt.serialize();
    }

    private String token(List<String> audience) throws Exception {
        return token(ISSUER, audience, Instant.now().plusSeconds(300), keys);
    }

    @Test
    void VYB0908_AC1_aTokenIssuedForThisApiIsAccepted() throws Exception {
        assertThat(decoder(AUDIENCE).decode(token(List.of(AUDIENCE))).getSubject()).isEqualTo("sub-1");
    }

    @Test
    void VYB0908_AC1_aTokenIssuedForAnotherClientInTheSameRealmIsRefused() throws Exception {
        assertThatThrownBy(() -> decoder(AUDIENCE).decode(token(List.of("pricing-tool"))))
            .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder(AUDIENCE).decode(token(List.of("account"))))
            .isInstanceOf(BadJwtException.class);
    }

    @Test
    void VYB0908_AC1_aTokenWithNoAudienceAtAllIsRefusedOnceAnAudienceIsRequired() throws Exception {
        assertThatThrownBy(() -> decoder(AUDIENCE).decode(token(null))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void VYB0908_AC1_oneMatchingAudienceAmongSeveralIsEnough() throws Exception {
        assertThat(decoder(AUDIENCE).decode(token(List.of("account", AUDIENCE, "other")))).isNotNull();
    }

    @Test
    void VYB0908_AC2_withNoAudienceConfiguredTheCheckIsOffButEverythingElseStillApplies() throws Exception {
        JwtDecoder open = decoder("");
        assertThat(open.decode(token(List.of("anything")))).isNotNull();
        assertThat(decoder(null).decode(token(null))).isNotNull();
        assertThatThrownBy(() -> open.decode(token("https://evil.example/realms/x", List.of("x"), Instant.now().plusSeconds(60), keys)))
            .as("wrong issuer").isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> open.decode(token(ISSUER, List.of("x"), Instant.now().minusSeconds(3600), keys)))
            .as("expired").isInstanceOf(BadJwtException.class);
    }

    @Test
    void VYB0908_AC2_aTokenSignedWithAnotherKeyIsRefusedWhateverItsAudience() throws Exception {
        KeyPair attacker = generate();
        String forged = token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), attacker);
        assertThatThrownBy(() -> decoder(AUDIENCE).decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void VYB0908_AC3_theConfiguredAudienceIsTrimmedAndTheSettingIsDefinedInApplicationYml() throws Exception {
        assertThat(decoder("  " + AUDIENCE + " ").decode(token(List.of(AUDIENCE)))).isNotNull();
        var yml = new org.springframework.boot.env.YamlPropertySourceLoader()
            .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
        assertThat(yml.getProperty("vyoog.jwt.audience")).isEqualTo("${JWT_AUDIENCE:}");
    }
}
