package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
import com.vyoog.search.SearchService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0908 (F09): search returns only what the caller's grants cover. Two separate portfolios each hold
 * a requirement, a capability, a finding and (shared) a glossary term, all carrying the same unique
 * search token, so any leak between them shows up as a wrong result.
 */
class SearchScopeIT extends IntegrationTestBase {

    @Autowired SearchService search;

    private String token;
    private Portfolio one, two;
    private UUID author;
    private Requirement reqOne, reqTwo, unplaced;
    private UUID findingOne, findingTwo, glossary;

    @BeforeEach
    void fixtures() {
        token = "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        author = newUser("author");
        one = newPortfolio();
        two = newPortfolio();
        jdbc.update("UPDATE capability SET name = ? WHERE id = ?", token + " capability one", one.capabilityId());
        jdbc.update("UPDATE capability SET name = ? WHERE id = ?", token + " capability two", two.capabilityId());
        reqOne = requirementService.create(token + " first", "The system shall " + token + " in one.", "FUNCTIONAL", "MEDIUM",
            Placement.capability(one.capabilityId()), author);
        reqTwo = requirementService.create(token + " second", "The system shall " + token + " in two.", "FUNCTIONAL", "MEDIUM",
            Placement.capability(two.capabilityId()), author);
        unplaced = requirementService.create(token + " unplaced", "The system shall " + token + " nowhere.", "FUNCTIONAL", "MEDIUM",
            Placement.unplaced(), author);
        // The after-commit enrichment of each create rescans the requirement and ends by embedding it. Its rescan resolves any
        // finding of that rule it does not re-raise, so the findings below are inserted only once it has finished.
        for (var r : List.of(reqOne, reqTwo, unplaced)) awaitEnrichment(r.getId());
        findingOne = finding("REQUIREMENT", reqOne.getId());
        findingTwo = finding("REQUIREMENT", reqTwo.getId());
        glossary = jdbc.queryForObject("INSERT INTO glossary_term (term, definition) VALUES (?, 'a term') RETURNING id",
            UUID.class, token + " term");
    }

    private void awaitEnrichment(UUID requirementId) {
        for (int i = 0; i < 200; i++) {
            Integer embedded = jdbc.queryForObject("SELECT count(*) FROM requirement_embedding WHERE requirement_id = ?", Integer.class, requirementId);
            if (embedded != null && embedded > 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("the enrichment of " + requirementId + " did not finish within 10 seconds");
    }

    private UUID finding(String type, UUID objectId) {
        return jdbc.queryForObject("""
            INSERT INTO finding (rule_key, fingerprint, object_type, object_id, severity, title)
            SELECT (SELECT key FROM gap_rule_template ORDER BY key LIMIT 1), ?, ?, ?, 'warn', ? RETURNING id""", UUID.class, UUID.randomUUID().toString(), type, objectId,
            token + " finding about " + objectId);
    }

    private List<UUID> idsOf(UUID user, String kind) {
        return search.search(token, user).stream().filter(r -> r.kind().equals(kind)).map(SearchService.Result::id).toList();
    }

    @Test
    void VYB0908_AC9_aPlatformGrantSeesEverythingIncludingUnplacedWork() {
        UUID admin = newAdministrator();
        assertThat(idsOf(admin, "REQUIREMENT")).containsExactlyInAnyOrder(reqOne.getId(), reqTwo.getId(), unplaced.getId());
        assertThat(idsOf(admin, "CAPABILITY")).containsExactlyInAnyOrder(one.capabilityId(), two.capabilityId());
        assertThat(idsOf(admin, "FINDING")).containsExactlyInAnyOrder(findingOne, findingTwo);
        assertThat(idsOf(admin, "GLOSSARY_TERM")).containsExactly(glossary);
    }

    @Test
    void VYB0908_AC9_aCapabilityGrantSeesThatCapabilityAndNothingFromTheOtherPortfolio() {
        UUID user = grantOnCapability(newUser("cap"), AccessRole.VIEWER, one.capabilityId());
        assertThat(idsOf(user, "REQUIREMENT")).containsExactly(reqOne.getId());
        assertThat(idsOf(user, "CAPABILITY")).containsExactly(one.capabilityId());
        assertThat(idsOf(user, "FINDING")).containsExactly(findingOne);
    }

    @Test
    void VYB0908_AC9_anApplicationGrantReachesItsCapabilitiesAndTheirRequirements() {
        UUID user = newUser("app");
        grants.grant(user, AccessRole.VIEWER, ScopeType.APP, two.applicationId(), null, user);
        assertThat(idsOf(user, "REQUIREMENT")).containsExactly(reqTwo.getId());
        assertThat(idsOf(user, "CAPABILITY")).containsExactly(two.capabilityId());
        assertThat(idsOf(user, "FINDING")).containsExactly(findingTwo);
    }

    @Test
    void VYB0908_AC9_aProductGrantReachesEverythingBeneathItAndOnlyThat() {
        UUID user = newUser("product");
        grants.grant(user, AccessRole.REVIEWER, ScopeType.PRODUCT, one.productId(), null, user);
        assertThat(idsOf(user, "REQUIREMENT")).containsExactly(reqOne.getId());
        assertThat(idsOf(user, "CAPABILITY")).containsExactly(one.capabilityId());
    }

    @Test
    void VYB0908_AC9_aRequirementPlacedAtTheProductOrApplicationLevelIsFoundByAGrantOnThatLevel() {
        Requirement atProduct = requirementService.create(token + " at product", "The system shall " + token + ".", "FUNCTIONAL",
            "MEDIUM", Placement.product(one.productId()), author);
        Requirement atApp = requirementService.create(token + " at app", "The system shall " + token + ".", "FUNCTIONAL",
            "MEDIUM", Placement.application(one.applicationId()), author);
        UUID onProduct = newUser("p");
        grants.grant(onProduct, AccessRole.VIEWER, ScopeType.PRODUCT, one.productId(), null, onProduct);
        assertThat(idsOf(onProduct, "REQUIREMENT")).contains(atProduct.getId(), atApp.getId(), reqOne.getId());
        UUID onApp = newUser("a");
        grants.grant(onApp, AccessRole.VIEWER, ScopeType.APP, one.applicationId(), null, onApp);
        assertThat(idsOf(onApp, "REQUIREMENT")).contains(atApp.getId(), reqOne.getId()).doesNotContain(atProduct.getId());
    }

    @Test
    void VYB0908_AC10_aPersonWithNoGrantFindsNothingAtAll() {
        UUID nobody = newUser("nobody");
        assertThat(search.search(token, nobody)).isEmpty();
    }

    @Test
    void VYB0908_AC10_aRevokedOrExpiredGrantFindsNothing() {
        UUID revoked = grantOnCapability(newUser("revoked"), AccessRole.VIEWER, one.capabilityId());
        assertThat(idsOf(revoked, "REQUIREMENT")).containsExactly(reqOne.getId());
        jdbc.update("UPDATE access_grant SET revoked_at = now() WHERE user_id = ?", revoked);
        assertThat(search.search(token, revoked)).isEmpty();

        UUID expired = grantOnCapability(newUser("expired"), AccessRole.VIEWER, one.capabilityId());
        jdbc.update("UPDATE access_grant SET expires_at = now() - interval '1 day' WHERE user_id = ?", expired);
        assertThat(search.search(token, expired)).isEmpty();
    }

    @Test
    void VYB0908_AC11_workPlacedNowhereIsVisibleToItsCreatorAndNotToOtherGrantHolders() {
        UUID other = grantOnCapability(newUser("other"), AccessRole.VIEWER, one.capabilityId());
        assertThat(idsOf(other, "REQUIREMENT")).doesNotContain(unplaced.getId());

        grantOnCapability(author, AccessRole.VIEWER, one.capabilityId());     // the creator needs some grant to search at all
        assertThat(idsOf(author, "REQUIREMENT")).contains(reqOne.getId(), unplaced.getId()).doesNotContain(reqTwo.getId());
    }

    @Test
    void VYB0908_AC11_findingsAboutAnythingButARequirementCapabilityOrLinkAreForPlatformHoldersOnly() {
        UUID clauseFinding = finding("CLAUSE", UUID.randomUUID());
        UUID cap = grantOnCapability(newUser("cap2"), AccessRole.VIEWER, one.capabilityId());
        assertThat(idsOf(cap, "FINDING")).doesNotContain(clauseFinding);
        assertThat(idsOf(newAdministrator(), "FINDING")).contains(clauseFinding);
    }

    @Test
    void VYB0908_AC11_aDeletedRequirementIsNotFoundByAnyone() {
        requirementService.delete(reqOne.getId(), "gone", newAdministrator());
        assertThat(idsOf(newAdministrator(), "REQUIREMENT")).doesNotContain(reqOne.getId());
    }

    @Test
    void VYB0908_AC12_aBlankQueryFindsNothing() {
        assertThat(search.search("  ", newAdministrator())).isEmpty();
    }
}
