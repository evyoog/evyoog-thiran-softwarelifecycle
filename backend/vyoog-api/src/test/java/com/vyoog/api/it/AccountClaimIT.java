package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AppUserService;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.TeamService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0961 (D49): an administrator joins a prepared directory entry to the account its person actually signed in with, when the address
 * Keycloak sent was not the one that was typed (the person then has a second, empty account and no access). Against a real PostgreSQL,
 * over HTTP with real tokens; the refusals leave both entries exactly as they were.
 */
@AutoConfigureMockMvc
class AccountClaimIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired AppUserService directory;
    @Autowired TeamService teams;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private JwtRequestPostProcessor admin() {
        UUID id = newAdministrator();
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, id);
        return jwt().jwt(j -> j.subject(sub).claim("email", sub.substring(4) + "@it.test").claim("preferred_username", sub.substring(4)).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor token(String subject, String email) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("preferred_username", email.substring(0, email.indexOf('@'))).claim("azp", "vyoog-web"));
    }

    /** A prepared entry with a role, and the account the person then signed in with under another address. */
    private record Pair(UUID prepared, UUID account, String subject, String realEmail, JwtRequestPostProcessor theirToken, Portfolio place) {}

    private Pair pair() throws Exception {
        Portfolio p = newPortfolio();
        UUID admin = newAdministrator();
        String tag = unique("pam");
        UUID prepared = directory.createLocalWithRole(tag + "@typed.test", "Pam " + tag, AccessRole.BUSINESS_ANALYST, ScopeType.CAPABILITY, p.capabilityId(), admin).getId();
        String subject = "kc-" + UUID.randomUUID();
        String realEmail = "pam.real." + tag + "@company.test";
        JwtRequestPostProcessor theirs = token(subject, realEmail);
        mvc.perform(get("/api/v1/me").with(theirs)).andExpect(status().isOk()).andExpect(jsonPath("$.activeGrants.length()").value(0)); // the second, empty account
        UUID account = jdbc.queryForObject("SELECT id FROM app_user WHERE subject = ?", UUID.class, subject);
        assertThat(account).isNotEqualTo(prepared);
        return new Pair(prepared, account, subject, realEmail, theirs, p);
    }

    private String claimBody(UUID account) { return "{\"accountId\":\"" + account + "\"}"; }

    private void assertUntouched(Pair x) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, x.account())).as("the account is still there").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, x.prepared())).as("the prepared entry is still unclaimed").startsWith("unclaimed:");
    }

    @Test
    void VYB0961_AC6_theAdministratorJoinsThePreparedEntryToTheAccountAndThePersonHasTheirAccessFromTheNextRequest() throws Exception {
        Pair x = pair();
        UUID manager = newUser("manager");
        jdbc.update("UPDATE app_user SET manager_id = ? WHERE id = ?", manager, x.prepared());
        UUID team = teams.create(unique("Team")).getId();
        teams.addMember(team, x.prepared());
        JwtRequestPostProcessor admin = admin();

        mvc.perform(post("/api/v1/users/" + x.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(x.account())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(x.prepared().toString()))
            .andExpect(jsonPath("$.email").value(x.realEmail())).andExpect(jsonPath("$.source").value("SSO"))
            .andExpect(jsonPath("$.lastSeenAt").isNotEmpty()).andExpect(jsonPath("$.activeGrantCount").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, x.account())).as("the empty account is gone").isZero();
        mvc.perform(get("/api/v1/me").with(x.theirToken())).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(x.prepared().toString()))
            .andExpect(jsonPath("$.activeGrants[0].role").value("BUSINESS_ANALYST"))
            .andExpect(jsonPath("$.activeGrants[0].scopeId").value(x.place().capabilityId().toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE subject = ?", Integer.class, x.subject())).as("one account for this person").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT manager_id FROM app_user WHERE id = ?", UUID.class, x.prepared())).as("what was prepared is kept").isEqualTo(manager);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_member WHERE team_id = ? AND user_id = ?", Integer.class, team, x.prepared())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'user.account_claimed' AND object_id = ?", Integer.class, x.prepared())).isEqualTo(1);
    }

    @Test
    void VYB0961_AC7_anAccountThatHoldsAccessBelongsToATeamOrHasSavedViewsIsNeverMergedAway() throws Exception {
        JwtRequestPostProcessor admin = admin();

        Pair withGrant = pair();
        grantOnCapability(withGrant.account(), AccessRole.VIEWER, withGrant.place().capabilityId());
        mvc.perform(post("/api/v1/users/" + withGrant.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(withGrant.account())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("holds access grants")));
        assertUntouched(withGrant);

        Pair inTeam = pair();
        teams.addMember(teams.create(unique("Team")).getId(), inTeam.account());
        mvc.perform(post("/api/v1/users/" + inTeam.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(inTeam.account())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("belongs to a team")));
        assertUntouched(inTeam);

        Pair withView = pair();
        jdbc.update("INSERT INTO saved_view (owner_id, name) VALUES (?, 'mine')", withView.account());
        mvc.perform(post("/api/v1/users/" + withView.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(withView.account())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("saved views")));
        assertUntouched(withView);
    }

    @Test
    void VYB0961_AC8_anAccountThatHasDoneAnythingIsRefusedByTheDatabaseItselfAndTheAnswerNamesWhat() throws Exception {
        Pair x = pair();
        newRequirement(x.place(), x.account()); // it authored a requirement
        JwtRequestPostProcessor admin = admin();

        mvc.perform(post("/api/v1/users/" + x.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(x.account())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already done work")))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("requirement")))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fkey"))));

        assertUntouched(x);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'user.account_claimed' AND object_id = ?", Integer.class, x.prepared())).isZero();
    }

    @Test
    void VYB0961_AC9_thePairMustBeAPreparedEntryAndARealSignInAndDifferentAndExisting() throws Exception {
        Pair x = pair();
        Pair claimed = pair();
        JwtRequestPostProcessor admin = admin();
        mvc.perform(post("/api/v1/users/" + claimed.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(claimed.account()))).andExpect(status().isOk());

        mvc.perform(post("/api/v1/users/" + claimed.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(x.account())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already signed in")));
        mvc.perform(post("/api/v1/users/" + x.account() + "/claim").with(admin).contentType(JSON).content(claimBody(x.account())))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/users/" + x.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(x.prepared())))
            .andExpect(status().isBadRequest());
        UUID anotherPrepared = directory.createLocal(unique("other") + "@typed.test", "Other", newAdministrator()).getId();
        mvc.perform(post("/api/v1/users/" + x.prepared() + "/claim").with(admin).contentType(JSON).content(claimBody(anotherPrepared)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("prepared entry")));
        mvc.perform(post("/api/v1/users/" + UUID.randomUUID() + "/claim").with(admin).contentType(JSON).content(claimBody(x.account())))
            .andExpect(status().isNotFound());
        assertUntouched(x);
    }

    @Test
    void VYB0961_AC10_onlyAnAdministratorMayJoinAccounts() throws Exception {
        Pair x = pair();
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, x.place().capabilityId());
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, analyst);

        mvc.perform(post("/api/v1/users/" + x.prepared() + "/claim").with(token(sub, sub.substring(4) + "@it.test")).contentType(JSON).content(claimBody(x.account())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/users/" + x.prepared() + "/claim").contentType(JSON).content(claimBody(x.account()))).andExpect(status().isUnauthorized());

        assertUntouched(x);
    }
}
