package com.vyoog.api;

import com.vyoog.attachments.AttachmentService;
import com.vyoog.evidence.TestRun;
import com.vyoog.evidence.TestRunRepository;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.platform.audit.AuditRetentionService;
import com.vyoog.signals.SignalsExportService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session 16: two claims from the "built, but real gaps remain" list turned out to
 * already be wrong or closeable with what session 14/15 stood up — this proves both,
 * with real beans against the live database and the real MinIO from session 14, not
 * mocks. Same explicit-name-only convention as LoadRehearsalRunner/
 * Session14VerificationRunner — invisible to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * DB_URL=... DB_USER=... DB_PASSWORD=... mvn -pl vyoog-api -am test \
 *   -Dtest=Session16VerificationRunner -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class Session16VerificationRunner {

    @Autowired JdbcTemplate jdbc;
    @Autowired VerificationService verificationService;
    @Autowired TestRunRepository testRuns;
    @Autowired AttachmentService attachmentService;
    @Autowired AuditRetentionService auditRetention;
    @Autowired SignalsExportService signalsExport;

    /**
     * VYB-0117: the "gap" as stated ("nothing populates it yet") was already false as
     * of session 9 — VerificationService.ingest writes real verification rows. VYB-0810
     * (session 26) removed the promotion this test used to also prove (APPROVED→
     * VERIFIED) — VERIFIED is no longer a requirement status, so this now proves the
     * opposite half deliberately: ingest a PASS result → a real `verification` row
     * exists → `requirement_verification_state` reflects it → `requirement.status`
     * stays exactly where it was, because evidence no longer writes to it.
     */
    @Test
    void verificationPredicateActuallyPopulatesAndPromotes() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('S16-VERIFY', 'Session 16 Verify') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'S16 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'S16 Cap') RETURNING id", UUID.class, appId);
        UUID reqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement)
            VALUES ('VY-S16VERIFY', ?, 'FUNCTIONAL', 'APPROVED', 'verify predicate title', 'verify predicate statement')
            RETURNING id
            """, UUID.class, capId);

        try {
            // Read-side predicate, before any evidence exists: must say not-verified.
            Boolean beforeVerified = jdbc.queryForObject(
                "SELECT is_verified FROM requirement_verification_state WHERE id = ?", Boolean.class, reqId);
            assertThat(beforeVerified).isFalse();

            TestRun run = testRuns.save(new TestRun("s16-build-1", "ci-s16"));
            var outcome = verificationService.ingest(run,
                new VerificationService.ResultInput("s16-test-key", "S16 test", VerificationResult.PASS, List.of("VY-S16VERIFY")));
            System.out.println("[verify] ingest outcome: verifiedRequirementKeys=" + outcome.verifiedRequirementKeys());

            Integer verificationRows = jdbc.queryForObject(
                "SELECT count(*) FROM verification WHERE requirement_id = ? AND result = 'PASS'", Integer.class, reqId);
            assertThat(verificationRows).isEqualTo(1);

            Boolean afterVerified = jdbc.queryForObject(
                "SELECT is_verified FROM requirement_verification_state WHERE id = ?", Boolean.class, reqId);
            assertThat(afterVerified).isTrue();

            String status = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, reqId);
            System.out.println("[verify] requirement.status after ingest: " + status);
            // VYB-0810: a passing test no longer promotes requirement.status — APPROVED
            // is this pipeline's terminal status, and evidence is tracked separately.
            assertThat(status).isEqualTo("APPROVED");
        } finally {
            jdbc.update("DELETE FROM verification WHERE requirement_id = ?", reqId);
            jdbc.update("DELETE FROM trace_link WHERE to_id = ? OR from_id = ?", reqId, reqId);
            jdbc.update("DELETE FROM requirement WHERE id = ?", reqId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM test_case WHERE key = 's16-test-key'");
            jdbc.update("DELETE FROM test_run WHERE build_label = 's16-build-1'");
        }
    }

    /**
     * VYB-0123: a real byte round-trip against the real MinIO this sandbox has had
     * since session 14 — upload, version bump on re-upload, download each version by
     * number, byte-for-byte equality. Never exercised before this test existed.
     */
    @Test
    void attachmentsRoundTripAgainstRealMinio() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('S16-ATTACH', 'Session 16 Attach') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'S16 Attach App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'S16 Attach Cap') RETURNING id", UUID.class, appId);
        UUID reqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, title, statement)
            VALUES ('VY-S16ATTACH', ?, 'FUNCTIONAL', 'attach title', 'attach statement') RETURNING id
            """, UUID.class, capId);
        UUID actor = jdbc.queryForObject(
            "INSERT INTO app_user (subject, email, display_name) VALUES ('s16-verify-subject', 's16@example.com', 'S16 Verifier') RETURNING id",
            UUID.class);

        try {
            byte[] v1Bytes = "session 16 attachment round-trip, version 1".getBytes(StandardCharsets.UTF_8);
            var uploaded1 = attachmentService.upload(reqId, "s16-notes.txt", "text/plain", v1Bytes, actor);
            System.out.println("[verify] uploaded attachment " + uploaded1.attachment().getId() + " v" + uploaded1.version().getVersion());
            assertThat(uploaded1.version().getVersion()).isEqualTo((short) 1);

            byte[] v2Bytes = "session 16 attachment round-trip, version 2 — replaces nothing, adds a version".getBytes(StandardCharsets.UTF_8);
            var uploaded2 = attachmentService.upload(reqId, "s16-notes.txt", "text/plain", v2Bytes, actor);
            assertThat(uploaded2.version().getVersion()).isEqualTo((short) 2);
            assertThat(uploaded2.attachment().getId()).isEqualTo(uploaded1.attachment().getId()); // same attachment, versioned not overwritten

            var downloadedCurrent = attachmentService.downloadCurrent(uploaded1.attachment().getId());
            assertThat(downloadedCurrent.bytes()).isEqualTo(v2Bytes);
            System.out.println("[verify] downloadCurrent returned v2, " + downloadedCurrent.bytes().length + " bytes, byte-identical");

            var downloadedV1 = attachmentService.download(uploaded1.attachment().getId(), (short) 1);
            assertThat(downloadedV1.bytes()).isEqualTo(v1Bytes);
            System.out.println("[verify] download(v1) still retrievable after v2 exists, byte-identical — versioned, not overwritten");
        } finally {
            jdbc.update("DELETE FROM attachment_version WHERE attachment_id IN (SELECT id FROM attachment WHERE requirement_id = ?)", reqId);
            jdbc.update("DELETE FROM attachment WHERE requirement_id = ?", reqId);
            jdbc.update("DELETE FROM requirement WHERE id = ?", reqId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", actor);
        }
    }

    /**
     * VYB-0723: a partition dated well outside the retention window gets detached and
     * renamed — real DDL against the real live database, not a dry-run description of
     * what it would do. Uses a throwaway partition (2020-01) so it can't collide with
     * anything real, and cleans up by dropping the archived table afterward (dropping
     * a detached, no-longer-partitioned table is plain DDL — not a row-level DELETE,
     * so the append-only trigger has nothing to object to).
     */
    @Test
    void archivesAPartitionPastRetention() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS audit_event_2020_01 PARTITION OF audit_event
            FOR VALUES FROM ('2020-01-01') TO ('2020-02-01')
            """);
        jdbc.update("""
            INSERT INTO audit_event (occurred_at, actor_type, action) VALUES ('2020-01-15', 'SYSTEM', 's16.archive-test')
            """);

        List<String> archived = auditRetention.archiveEligiblePartitions();
        System.out.println("[verify] archived partitions: " + archived);
        assertThat(archived).contains("audit_event_archive_2020_01");

        Integer stillInParent = jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE action = 's16.archive-test'", Integer.class);
        assertThat(stillInParent).isEqualTo(0); // no longer part of the partitioned table's view

        Integer inArchive = jdbc.queryForObject(
            "SELECT count(*) FROM audit_event_archive_2020_01 WHERE action = 's16.archive-test'", Integer.class);
        assertThat(inArchive).isEqualTo(1); // but the row itself still exists, renamed not destroyed

        jdbc.execute("DROP TABLE audit_event_archive_2020_01"); // detached — a plain DDL drop, not a row DELETE
    }

    /**
     * VYB-0465/0507: a real outbound POST, to a real local HTTP server standing in
     * for "the delivery tool" (this sandbox has no real one) — signed, received,
     * verified against the same secret, not just constructed and inspected in memory.
     * Requires the "planning" connection to already be configured (done via SQL
     * before this runs — see the session's own notes) with
     * {@code pushUrl=http://127.0.0.1:8999/} and a shared secret, and requires
     * {@code receiver.py} to already be running on that port.
     */
    @Test
    void signalsPushArrivesAtRealReceiverWithAValidSignature() throws Exception {
        Path logPath = Path.of(
            "/tmp/claude-1000/-home-vyoog-Documents-vyg-requirements/dc41a20a-4575-4b40-be1f-2bc2dc24f446/scratchpad/receiver.log");
        Files.deleteIfExists(logPath);

        var result = signalsExport.pushToDeliveryTool(List.of(), UUID.randomUUID());
        System.out.println("[verify] push result: success=" + result.success() + " statusCode=" + result.statusCode());
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);

        assertThat(Files.exists(logPath)).isTrue();
        String received = Files.readString(logPath);
        System.out.println("[verify] receiver actually logged: " + received);
        // VYB-0767: an earlier version of this assertion only checked the log text
        // for the substring "signature" — which is present in the log line's own
        // literal label regardless of whether a real header ever arrived, so it
        // passed even while the receiver was checking the wrong header name and
        // logging an empty value. Assert on the receiver's own computed verdict
        // instead — it recomputes the HMAC independently from the shared secret and
        // the exact bytes it received, so this can only pass if a genuine,
        // correctly-signed request arrived on the wire.
        assertThat(received).contains("hmacValid=True").contains("requirementCount");
        assertThat(received).doesNotContain("receivedSignature=\n");

        Integer pushAudited = jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE action = 'signals.pushed'", Integer.class);
        assertThat(pushAudited).isGreaterThanOrEqualTo(1);
    }
}
