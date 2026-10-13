package com.vyoog.api.rehearsal;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vyoog.api.config.SecurityConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * VYB-0959: the tokens of the rehearsals. There is no Keycloak in the rehearsal environment, so the application is given a decoder for
 * a throwaway key and the rehearsal signs real JWTs with it. Everything else is as in production: the same token validator
 * ({@link SecurityConfig#tokenValidator}), the same filter chain, the same guards. Never used outside the rehearsal runners.
 */
public final class RehearsalTokens {

    public static final String ISSUER = "https://rehearsal.local/realms/vyoog";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public RehearsalTokens() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER, ""));
        return decoder;
    }

    /** A signed token for one person, valid for an hour: the claims a web sign-in carries. */
    public String tokenFor(String subject, String email, String username) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(subject).claim("email", email).claim("preferred_username", username).claim("azp", "vyoog-web")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
