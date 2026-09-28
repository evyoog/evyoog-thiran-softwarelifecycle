package com.vyoog.deployment;

import com.vyoog.evidence.IngestedCommit;
import com.vyoog.evidence.IngestedCommitRepository;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkRepository;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.HashSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0481/0482. Presence derives from code links, not a manual list (VYB-0482 AC1):
 * the CI caller supplies which commits are in this build — the one fact only CI
 * actually knows (a git diff since the last deploy) — and this derives which
 * requirements those commits' own {@code Requirement:} trailers already linked
 * (VYB-0316), rather than asking the caller to separately enumerate requirement ids.
 */
@Service
public class DeploymentService {

    private final EnvironmentRepository environments;
    private final DeploymentRepository deployments;
    private final IngestedCommitRepository commits;
    private final TraceLinkRepository links;
    private final RequirementRepository requirements;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DeploymentService(EnvironmentRepository environments, DeploymentRepository deployments,
                              IngestedCommitRepository commits, TraceLinkRepository links,
                              RequirementRepository requirements, JdbcTemplate jdbc, AuditService audit) {
        this.environments = environments;
        this.deployments = deployments;
        this.commits = commits;
        this.links = links;
        this.requirements = requirements;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Environment createEnvironment(String name, short ordinal) {
        return environments.save(new Environment(name, ordinal));
    }

    public List<Environment> environments() {
        return environments.findAllByOrderByOrdinalAsc();
    }

    @Transactional
    public Deployment recordDeployment(UUID environmentId, String buildLabel, boolean succeeded, List<String> commitShas) {
        environments.findById(environmentId).orElseThrow();
        Deployment deployment = deployments.save(new Deployment(environmentId, buildLabel, succeeded));

        Set<UUID> requirementIds = new HashSet<>();
        for (String sha : commitShas == null ? List.<String>of() : commitShas) {
            commits.findBySha(sha).ifPresent(commit -> {
                for (TraceLink link : links.findAllByFromTypeAndFromId(TraceObjectType.CODE, commit.getId())) {
                    if (link.getLinkType() == TraceLinkType.IMPLEMENTS && link.getToType() == TraceObjectType.REQUIREMENT) {
                        requirementIds.add(link.getToId());
                    }
                }
            });
        }

        for (UUID reqId : requirementIds) {
            requirements.findById(reqId).ifPresent(r ->
                jdbc.update("""
                    INSERT INTO deployment_requirement (deployment_id, requirement_id, revision) VALUES (?,?,?)
                    ON CONFLICT DO NOTHING
                    """, deployment.getId(), reqId, r.getRevision()));
        }

        audit.recordService("deployment.recorded", "DEPLOYMENT", deployment.getId(),
            Map.of("environmentId", environmentId.toString(), "buildLabel", buildLabel,
                   "succeeded", succeeded, "requirementsPresent", requirementIds.size()));
        return deployment;
    }

    /**
     * VYB-0374: one environment and what is actually in it.
     *
     * <p>{@code requirementCount} counts distinct requirements present in that
     * environment's most recent successful deployment — not every deployment ever, which
     * would keep counting things a later build removed. {@code buildLabel} and
     * {@code deployedAt} are null when nothing has ever been deployed there: Principle 8
     * says absence renders as "not connected", never as a zero somebody might read as a
     * measurement.
     */
    public record EnvironmentSummary(String id, String name, short ordinal, String buildLabel,
                                      Instant deployedAt, long requirementCount, boolean succeeded) {}

    /**
     * Every environment with its current contents, ordered as the pipeline runs.
     *
     * <p>Deliberately one query rather than a loop over {@link #environments()}: this
     * renders on every visit to My Work, and an environment-per-request pattern is how a
     * four-box strip turns into nine round trips.
     */
    public List<EnvironmentSummary> environmentSummaries() {
        return jdbc.query("""
            WITH latest AS (
              SELECT DISTINCT ON (environment_id)
                     environment_id, id, build_label, deployed_at, succeeded
              FROM deployment
              WHERE succeeded
              ORDER BY environment_id, deployed_at DESC
            )
            SELECT e.id, e.name, e.ordinal,
                   latest.build_label, latest.deployed_at, latest.succeeded,
                   COALESCE((SELECT count(DISTINCT dr.requirement_id)
                             FROM deployment_requirement dr
                             WHERE dr.deployment_id = latest.id), 0) AS requirement_count
            FROM environment e
            LEFT JOIN latest ON latest.environment_id = e.id
            ORDER BY e.ordinal, e.name
            """,
            (rs, n) -> new EnvironmentSummary(
                rs.getString("id"), rs.getString("name"), rs.getShort("ordinal"),
                rs.getString("build_label"),
                rs.getTimestamp("deployed_at") == null ? null : rs.getTimestamp("deployed_at").toInstant(),
                rs.getLong("requirement_count"),
                rs.getObject("succeeded") != null && rs.getBoolean("succeeded")));
    }

    /** One deployment across every environment, newest first — the activity feed. */
    public record RecentDeployment(String id, String environmentName, String buildLabel,
                                    Instant deployedAt, boolean succeeded, long requirementCount) {}

    public List<RecentDeployment> recent(int limit) {
        return jdbc.query("""
            SELECT d.id, e.name AS environment_name, d.build_label, d.deployed_at, d.succeeded,
                   (SELECT count(*) FROM deployment_requirement dr WHERE dr.deployment_id = d.id)
                     AS requirement_count
            FROM deployment d
            JOIN environment e ON e.id = d.environment_id
            ORDER BY d.deployed_at DESC
            LIMIT ?
            """,
            (rs, n) -> new RecentDeployment(
                rs.getString("id"), rs.getString("environment_name"), rs.getString("build_label"),
                rs.getTimestamp("deployed_at").toInstant(), rs.getBoolean("succeeded"),
                rs.getLong("requirement_count")),
            limit);
    }

    /**
     * Requirements that cannot ship, and why — Principle 3 read as a release gate.
     *
     * <p>"Verified" is a predicate, not a flag: a requirement is shippable only when a
     * test passed against its <em>current</em> revision. These are the three ways that
     * fails, each stated as its own reason rather than collapsed into one "not ready"
     * count that nobody can act on.
     */
    public record ReleaseBlocker(String requirementId, String key, String title, String reason) {}

    public List<ReleaseBlocker> blockedFromRelease(int limit) {
        // requirement_verification_state is the one definition of the Verified predicate
        // (Principle 3, V001). Re-deriving "has a passing test at the current revision"
        // here would create a second answer that drifts from the one the rest of the
        // application uses, and the two disagreeing is worse than neither existing.
        return jdbc.query("""
            SELECT r.id, r.key, r.title,
                   CASE
                     WHEN vs.has_stale_evidence THEN
                       'Requirement moved to revision ' || r.revision
                       || ' — the last passing test was against an earlier one'
                     ELSE 'Approved, never verified'
                   END AS reason
            FROM requirement r
            JOIN requirement_verification_state vs ON vs.id = r.id
            WHERE r.deleted_at IS NULL
              AND r.status = 'APPROVED'
              AND NOT vs.is_verified
            ORDER BY vs.has_stale_evidence DESC, r.key
            LIMIT ?
            """,
            (rs, n) -> new ReleaseBlocker(rs.getString("id"), rs.getString("key"),
                rs.getString("title"), rs.getString("reason")),
            limit);
    }

    public record PresenceRow(String requirementId, String key, int revision) {}

    public List<PresenceRow> presence(UUID deploymentId) {
        return jdbc.query("""
            SELECT dr.requirement_id, r.key, dr.revision FROM deployment_requirement dr
            JOIN requirement r ON r.id = dr.requirement_id
            WHERE dr.deployment_id = ? ORDER BY r.key
            """,
            (rs, n) -> new PresenceRow(rs.getString("requirement_id"), rs.getString("key"), rs.getInt("revision")),
            deploymentId);
    }
}
