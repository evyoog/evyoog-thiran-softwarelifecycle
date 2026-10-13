package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0955 (F21, D47): a person's own choices, the administrator's channel settings, and who may change either. Against a real
 * PostgreSQL, over HTTP with real tokens.
 */
@AutoConfigureMockMvc
class NotificationPreferencesIT extends NotificationITBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private JwtRequestPostProcessor tokenOf(UUID user) {
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, user);
        return jwt().jwt(j -> j.subject(sub).claim("email", sub.substring(4) + "@it.test").claim("preferred_username", sub.substring(4)).claim("azp", "vyoog-web"));
    }

    private JsonNode kind(JsonNode view, String key) {
        for (JsonNode k : view.get("kinds")) if (key.equals(k.get("kind").asText())) return k;
        throw new AssertionError("no kind " + key);
    }

    private JsonNode getJson(String url, JwtRequestPostProcessor who) throws Exception {
        return json.readTree(mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode putJson(String url, String body, JwtRequestPostProcessor who) throws Exception {
        return json.readTree(mvc.perform(put(url).with(who).contentType(JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ a person's own choices

    @Test
    void VYB0955_AC21_everyKindIsListedWithItsDefaultInboxOnForAllAndEmailOnForTheUrgentOnes() throws Exception {
        JwtRequestPostProcessor me = tokenOf(newUser("me"));

        JsonNode view = getJson("/api/v1/notifications/preferences", me);

        assertThat(view.get("emailAvailable").asBoolean()).as("this deployment has email set up").isTrue();
        assertThat(view.get("kinds")).hasSizeGreaterThanOrEqualTo(15);
        for (JsonNode k : view.get("kinds")) {
            assertThat(k.get("inbox").asBoolean()).as(k.get("kind").asText() + " inbox default").isTrue();
            assertThat(k.get("email").asBoolean()).as(k.get("kind").asText() + " email default is its urgency").isEqualTo(k.get("urgent").asBoolean());
            assertThat(k.get("inboxIsDefault").asBoolean()).isTrue();
            assertThat(k.get("label").asText()).isNotBlank();
            assertThat(k.get("description").asText()).isNotBlank();
        }
        assertThat(kind(view, "review-requested").get("urgent").asBoolean()).isTrue();
        assertThat(kind(view, "requirement-approved").get("urgent").asBoolean()).isFalse();
    }

    @Test
    void VYB0955_AC22_aChoiceIsKeptPerKindAndChannelAndCanBePutBackToItsDefaultAndIsAudited() throws Exception {
        UUID user = newUser("me");
        JwtRequestPostProcessor me = tokenOf(user);

        JsonNode after = putJson("/api/v1/notifications/preferences", "{\"changes\":[{\"kind\":\"requirement-approved\",\"channel\":\"EMAIL\",\"enabled\":true},{\"kind\":\"mentioned\",\"channel\":\"INBOX\",\"enabled\":false}]}", me);

        assertThat(kind(after, "requirement-approved").get("email").asBoolean()).isTrue();
        assertThat(kind(after, "requirement-approved").get("emailIsDefault").asBoolean()).isFalse();
        assertThat(kind(after, "mentioned").get("inbox").asBoolean()).isFalse();
        assertThat(kind(after, "mentioned").get("email").asBoolean()).as("the other channel is untouched").isTrue();
        assertThat(kind(after, "review-requested").get("inboxIsDefault").asBoolean()).as("other kinds are untouched").isTrue();
        assertThat(getJson("/api/v1/notifications/preferences", me).get("kinds")).isEqualTo(after.get("kinds"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'notification.preferences-changed' AND object_id = ? AND actor_id = ?",
            Integer.class, user, user)).isEqualTo(1);

        JsonNode reset = putJson("/api/v1/notifications/preferences", "{\"changes\":[{\"kind\":\"mentioned\",\"channel\":\"INBOX\",\"enabled\":null}]}", me);
        assertThat(kind(reset, "mentioned").get("inbox").asBoolean()).isTrue();
        assertThat(kind(reset, "mentioned").get("inboxIsDefault").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preference WHERE user_id = ? AND kind = 'mentioned'", Integer.class, user)).isZero();
    }

    @Test
    void VYB0955_AC23_aPersonChoosesOnlyForThemselvesAndOnlyInboxOrEmailAndOnlyKindsThatExist() throws Exception {
        UUID user = newUser("me");
        UUID other = newUser("other");
        JwtRequestPostProcessor me = tokenOf(user);

        mvc.perform(put("/api/v1/notifications/preferences").with(me).contentType(JSON)
            .content("{\"changes\":[{\"kind\":\"mentioned\",\"channel\":\"CHAT\",\"enabled\":true}]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/notifications/preferences").with(me).contentType(JSON)
            .content("{\"changes\":[{\"kind\":\"no-such-kind\",\"channel\":\"EMAIL\",\"enabled\":true}]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/notifications/preferences").with(me).contentType(JSON)
            .content("{\"changes\":[{\"kind\":\"mentioned\",\"channel\":\"SMS\",\"enabled\":true}]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/notifications/preferences").contentType(JSON).content("{\"changes\":[]}")).andExpect(status().isUnauthorized());

        putJson("/api/v1/notifications/preferences", "{\"changes\":[{\"kind\":\"mentioned\",\"channel\":\"EMAIL\",\"enabled\":false}]}", me);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preference WHERE user_id = ?", Integer.class, other)).isZero();
    }

    // ------------------------------------------------------------------ the administrator's settings

    @Test
    void VYB0955_AC24_theSettingsSayWhetherEachChannelIsSetUpAndNeverShowTheAddressOrTheMailPassword() throws Exception {
        JwtRequestPostProcessor admin = tokenOf(newAdministrator());

        String raw = mvc.perform(get("/api/v1/settings/notifications").with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.emailConfigured").value(true)).andExpect(jsonPath("$.chatConfigured").value(true))
            .andExpect(jsonPath("$.linksConfigured").value(true))
            .andReturn().getResponse().getContentAsString();

        assertThat(raw).doesNotContain("secret-signature").doesNotContain(CHAT.url()).doesNotContain("vyoog@app.test");
        JsonNode view = json.readTree(raw);
        List<String> chatKinds = new java.util.ArrayList<>();
        view.get("chatKinds").forEach(k -> chatKinds.add(k.get("kind").asText()));
        assertThat(chatKinds).contains("review-requested", "requirement-approved", "release-state-changed", "test-failed")
            .as("personal kinds cannot go to a shared channel").doesNotContain("mentioned", "clarification-raised", "defect-routed-developer");
    }

    @Test
    void VYB0955_AC25_theAdministratorChoosesWhichKindsGoToChatAndItIsAudited() throws Exception {
        UUID adminId = newAdministrator();
        JwtRequestPostProcessor admin = tokenOf(adminId);

        JsonNode after = putJson("/api/v1/settings/notifications/chat-kinds", "{\"kinds\":[\"release-state-changed\",\"test-failed\"]}", admin);

        List<String> on = new java.util.ArrayList<>();
        after.get("chatKinds").forEach(k -> { if (k.get("enabled").asBoolean()) on.add(k.get("kind").asText()); });
        assertThat(on).containsExactlyInAnyOrder("release-state-changed", "test-failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'notification.chat-kinds-changed' AND actor_id = ?", Integer.class, adminId)).isEqualTo(1);

        mvc.perform(put("/api/v1/settings/notifications/chat-kinds").with(admin).contentType(JSON).content("{\"kinds\":[\"mentioned\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/settings/notifications/chat-kinds").with(admin).contentType(JSON).content("{\"kinds\":[\"nonsense\"]}")).andExpect(status().isBadRequest());
        putJson("/api/v1/settings/notifications/chat-kinds", "{\"kinds\":[]}", admin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_chat_kind", Integer.class)).isZero();
    }

    @Test
    void VYB0955_AC26_onlyAnAdministratorMayReadOrChangeTheChannelSettingsOrSendATest() throws Exception {
        Portfolio p = newPortfolio();
        UUID analyst = grant(newUser("analyst"), AccessRole.BUSINESS_ANALYST, ScopeType.CAPABILITY, p.capabilityId());
        UUID approver = grant(newUser("approver"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId());
        for (UUID person : List.of(analyst, approver)) {
            JwtRequestPostProcessor who = tokenOf(person);
            mvc.perform(get("/api/v1/settings/notifications").with(who)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/settings/notifications/chat-kinds").with(who).contentType(JSON).content("{\"kinds\":[]}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/settings/notifications/test").with(who).contentType(JSON).content("{\"channel\":\"CHAT\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/settings/notifications")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_chat_kind", Integer.class)).isZero();
    }

    @Test
    void VYB0955_AC27_anAdministratorCanSendATestToEachChannelAndSeeItArrive() throws Exception {
        UUID adminId = newAdministrator();
        JwtRequestPostProcessor admin = tokenOf(adminId);
        String adminEmail = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, adminId);
        int chatBefore = CHAT.posts().size();

        mvc.perform(post("/api/v1/settings/notifications/test").with(admin).contentType(JSON).content("{\"channel\":\"EMAIL\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/v1/settings/notifications/test").with(admin).contentType(JSON).content("{\"channel\":\"CHAT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/v1/settings/notifications/test").with(admin).contentType(JSON).content("{\"channel\":\"INBOX\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false));
        mvc.perform(post("/api/v1/settings/notifications/test").with(admin).contentType(JSON).content("{\"channel\":\"PIGEON\"}")).andExpect(status().isBadRequest());

        assertThat(SMTP.received().stream().filter(m -> m.to().contains(adminEmail) && m.subject().equals("[Vyoog] Test message"))).hasSize(1);
        assertThat(CHAT.posts().size() - chatBefore).isEqualTo(1);

        CHAT.answerWith(500);
        try {
            mvc.perform(post("/api/v1/settings/notifications/test").with(admin).contentType(JSON).content("{\"channel\":\"CHAT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("HTTP 500")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-signature"))));
        } finally {
            CHAT.answerWith(202);
        }
    }
}
