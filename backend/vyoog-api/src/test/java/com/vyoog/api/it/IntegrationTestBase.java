package com.vyoog.api.it;

import com.vyoog.api.TestDatabaseProperties;
import com.vyoog.identity.AccessGrantService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementService;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * VYB-0907: the base of the integration tests ({@code *IT}, run by Failsafe in {@code mvn verify}).
 *
 * <p>They boot the whole application against a real PostgreSQL 16 with pgvector, with every Flyway
 * migration applied, and call the real services: no repository or service is mocked. The database is
 * a throwaway Testcontainers one, or the docker-compose one if {@code DB_URL} points at localhost
 * ({@link com.vyoog.testkit.LocalDatabase}); a non-local database is refused.
 *
 * <p>Isolation: tests commit, as production does (several services write with both JPA and
 * JdbcTemplate and rely on separate transactions), and so every test makes its own uniquely named
 * product, people and requirements and asserts only on those. Nothing is cleaned up: against a
 * throwaway container there is nothing to clean, and against a local database the leftovers are
 * test rows, never anything a test reads. The object store is stubbed; nothing else is.
 */
@SpringBootTest
@Import(IntegrationTestBase.StubStorage.class)
public abstract class IntegrationTestBase {

    static {
        TestDatabaseProperties.exportToSystemProperties(); // before the context starts; see that class for why
    }

    /** The attachment service pings its bucket on startup; there is no MinIO in the test environment. */
    @TestConfiguration
    static class StubStorage {
        @Bean
        @Primary
        S3Client stubS3Client() {
            return Mockito.mock(S3Client.class);
        }
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserProvisioningService users;
    @Autowired protected AccessGrantService grants;
    @Autowired protected RequirementService requirementService;

    /** A product, application and capability that exist only for this test. */
    protected record Portfolio(UUID productId, UUID applicationId, UUID capabilityId) {}

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected Portfolio newPortfolio() {
        String tag = unique("IT");
        UUID product = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES (?, ?) RETURNING id", UUID.class, tag, tag + " product");
        UUID app = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, ?) RETURNING id", UUID.class, product, tag + " app");
        UUID cap = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, ?) RETURNING id", UUID.class, app, tag + " cap");
        return new Portfolio(product, app, cap);
    }

    protected UUID newUser(String tag) {
        String id = unique(tag);
        return users.upsert("sub-" + id, id + "@it.test", id).getId();
    }

    protected UUID newAdministrator() {
        UUID id = newUser("admin");
        grants.grant(id, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, id);
        return id;
    }

    protected UUID grantOnCapability(UUID user, AccessRole role, UUID capabilityId) {
        grants.grant(user, role, ScopeType.CAPABILITY, capabilityId, null, user);
        return user;
    }

    protected Requirement newRequirement(Portfolio p, UUID author) {
        return requirementService.create(unique("Title"), "The system shall " + unique("do") + ".", "FUNCTIONAL",
            "MEDIUM", Placement.capability(p.capabilityId()), author);
    }

    protected int auditCount(UUID objectId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ? AND action = ?",
            Integer.class, objectId, action);
    }

    protected int revisionRows(UUID requirementId) {
        return jdbc.queryForObject("SELECT count(*) FROM requirement_revision WHERE requirement_id = ?",
            Integer.class, requirementId);
    }

    /**
     * Takes a requirement through the real lifecycle to APPROVED: its author submits it (with the
     * explicit override reason a requirement with no acceptance criteria needs), an administrator
     * marks it reviewed and approves it.
     */
    protected Requirement approved(Requirement r, UUID author, UUID administrator) {
        r = requirementService.transition(r.getId(), r.getRevision(),
            com.vyoog.requirements.RequirementStatus.IN_REVIEW, "no criteria yet, submitted on purpose", author);
        r = requirementService.transition(r.getId(), r.getRevision(),
            com.vyoog.requirements.RequirementStatus.REVIEWED, null, administrator);
        return requirementService.transition(r.getId(), r.getRevision(),
            com.vyoog.requirements.RequirementStatus.APPROVED, null, administrator);
    }
}
