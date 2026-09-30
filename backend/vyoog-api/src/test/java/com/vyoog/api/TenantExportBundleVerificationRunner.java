package com.vyoog.api;

import com.vyoog.attachments.AttachmentService;
import com.vyoog.platform.export.TenantExportService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VYB-0732: {@code TenantExportService.exportBundle()} proven against a real, live
 * MinIO — a real attachment is uploaded through {@link AttachmentService} (the same
 * path {@code AttachmentController} uses), then the bundle is built and unzipped to
 * confirm the exact bytes round-trip, alongside a genuinely-missing storage key
 * (never uploaded) to confirm a fetch failure is disclosed in {@code
 * missingStorageKeys} rather than silently dropped or failing the whole export. Same
 * explicit-name-only convention as every other {@code *VerificationRunner}: invisible
 * to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   mvn -pl vyoog-api -am test -Dtest=TenantExportBundleVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class TenantExportBundleVerificationRunner extends VerificationRunnerBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired AttachmentService attachments;
    @Autowired TenantExportService export;

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void bundlesARealUploadedAttachmentsBytesAndFlagsAGenuinelyMissingOneRatherThanFailingTheWholeExport() throws IOException {
        UUID productId = null, appId = null, capId = null, requirementId = null, ghostAttachmentId = null;

        try {
            productId = jdbc.queryForObject(
                "INSERT INTO product (key, name) VALUES ('S20-EXP', 'Session 20 Export') RETURNING id", UUID.class);
            appId = jdbc.queryForObject(
                "INSERT INTO application (product_id, name) VALUES (?, 'S20 App') RETURNING id", UUID.class, productId);
            capId = jdbc.queryForObject(
                "INSERT INTO capability (application_id, name) VALUES (?, 'S20 Cap') RETURNING id", UUID.class, appId);
            requirementId = jdbc.queryForObject("""
                INSERT INTO requirement (key, capability_id, type, title, statement)
                VALUES ('S20-EXP-1', ?, 'FUNCTIONAL', 'Export fixture', 'The system shall export attachments.')
                RETURNING id""", UUID.class, capId);

            byte[] realBytes = "hello export bundle".getBytes(StandardCharsets.UTF_8);
            var upload = attachments.upload(requirementId, "note.txt", "text/plain", realBytes, null);
            String realStorageKey = upload.version().getStorageKey();

            // A row the manifest will dump but object storage never actually holds —
            // proves a fetch failure is disclosed, not silently swallowed or fatal.
            ghostAttachmentId = jdbc.queryForObject(
                "INSERT INTO attachment (requirement_id, filename, current_version) VALUES (?, 'ghost.txt', 1) RETURNING id",
                UUID.class, requirementId);
            String ghostStorageKey = "requirements/never-uploaded/" + UUID.randomUUID();
            jdbc.update("""
                INSERT INTO attachment_version (attachment_id, version, storage_key, content_type, size_bytes)
                VALUES (?, 1, ?, 'text/plain', 0)""", ghostAttachmentId, ghostStorageKey);

            TenantExportService.ExportBundle bundle = export.exportBundle();
            System.out.println("[verify] attachmentCount=" + bundle.attachmentCount()
                + " bundledCount=" + bundle.bundledCount() + " missingStorageKeys=" + bundle.missingStorageKeys());

            assertThat(bundle.missingStorageKeys()).contains(ghostStorageKey);
            assertThat(bundle.bundledCount()).isGreaterThanOrEqualTo(1);

            Map<String, byte[]> zipped = unzip(bundle.zipBytes());
            assertThat(zipped).containsKey("manifest.json");
            assertThat(zipped).containsKey("attachments/" + realStorageKey);
            assertThat(zipped.get("attachments/" + realStorageKey)).isEqualTo(realBytes);
            assertThat(zipped).doesNotContainKey("attachments/" + ghostStorageKey); // never fetched, so never zipped
        } finally {
            if (ghostAttachmentId != null) {
                jdbc.update("DELETE FROM attachment_version WHERE attachment_id = ?", ghostAttachmentId);
                jdbc.update("DELETE FROM attachment WHERE id = ?", ghostAttachmentId);
            }
            if (requirementId != null) {
                jdbc.update("DELETE FROM attachment_version WHERE attachment_id IN " +
                    "(SELECT id FROM attachment WHERE requirement_id = ?)", requirementId);
                jdbc.update("DELETE FROM attachment WHERE requirement_id = ?", requirementId);
                jdbc.update("DELETE FROM requirement_revision WHERE requirement_id = ?", requirementId);
                jdbc.update("DELETE FROM requirement WHERE id = ?", requirementId);
            }
            if (capId != null) jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            if (appId != null) jdbc.update("DELETE FROM application WHERE id = ?", appId);
            if (productId != null) jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }
}
