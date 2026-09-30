package com.vyoog.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * VYB-0048b: the message somebody sees when sign-in is switched off by configuration.
 *
 * <p>Worth a test because it is the only thing anybody has to go on: it previously
 * announced that "no ROPC client is registered" when a client id was in fact configured
 * and only the secret was absent, and pointed at D7 — a decision about a different,
 * public, PKCE-only client — instead of D8, which is the one that governs this. Both
 * sent the reader looking for the wrong thing.
 */
class RopcConfigurationMessageTest {

    private KeycloakPasswordGrantService serviceWith(String clientId, String secret) {
        KeycloakPasswordGrantService s = new KeycloakPasswordGrantService(new ObjectMapper());
        ReflectionTestUtils.setField(s, "clientId", clientId);
        ReflectionTestUtils.setField(s, "clientSecret", secret);
        return s;
    }

    @Test
    void VYB0048b_AC1_aMissingSecretNamesTheSecretAndNotTheClient() {
        assertThatThrownBy(() -> serviceWith("eVyoog", "").login("a", "b"))
            .hasMessageContaining("KEYCLOAK_ROPC_CLIENT_SECRET is empty")
            .hasMessageContaining("eVyoog")           // says which client is configured
            .hasMessageContaining("D21")
            .hasMessageNotContainingAny("D7", "no ROPC client is registered");
    }

    @Test
    void VYB0048b_AC1_bothMissingSaysBothRatherThanGuessing() {
        assertThatThrownBy(() -> serviceWith("", null).refresh("t"))
            .hasMessageContaining("neither KEYCLOAK_ROPC_CLIENT_ID nor KEYCLOAK_ROPC_CLIENT_SECRET");
    }

    @Test
    void VYB0048b_AC1_theSecretItselfIsNeverEchoedIntoTheMessage() {
        // The check runs before any network call, so a configured pair gets past it; what
        // matters is that nothing here ever prints the secret back at the caller.
        assertThatThrownBy(() -> serviceWith("eVyoog", "s3cret-value").login("a", "b"))
            .hasMessageNotContaining("s3cret-value");
    }
}
