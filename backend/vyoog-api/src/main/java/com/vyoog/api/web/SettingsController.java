package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.BootstrapRefusedException;
import com.vyoog.identity.TenantBootstrapService;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.platform.audit.AuditRetentionService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.platform.config.AppConfigService;
import com.vyoog.platform.export.TenantExportService;
import com.vyoog.tasks.TaskService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0730–0734/0757. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final AppConfigService config;
    private final TaskService tasks;
    private final TenantExportService export;
    private final UserProvisioningService provisioning;
    private final AuditService audit;
    private final PrincipalGuard guard;
    private final TenantBootstrapService bootstrap;
    private final com.vyoog.platform.reset.TenantHardResetService hardReset;
    private final AuditRetentionService retention;

    /** VYB-0901: one-time token from the environment. Empty (the default) means bootstrap is switched off. */
    @org.springframework.beans.factory.annotation.Value("${vyoog.bootstrap.token:}")
    private String bootstrapToken;

    public SettingsController(AppConfigService config, TaskService tasks, TenantExportService export,
                               UserProvisioningService provisioning, AuditService audit, PrincipalGuard guard,
                               TenantBootstrapService bootstrap, com.vyoog.platform.reset.TenantHardResetService hardReset,
                               AuditRetentionService retention) {
        this.config = config;
        this.tasks = tasks;
        this.export = export;
        this.provisioning = provisioning;
        this.audit = audit;
        this.guard = guard;
        this.bootstrap = bootstrap;
        this.hardReset = hardReset;
        this.retention = retention;
    }

    public record BootstrapStatus(boolean bootstrapped) {}

    /** VYB-0730: no ADMINISTRATOR guard here on purpose — before this runs, none exists to hold one. Safe to leave reachable, since it can only ever succeed once. */
    @GetMapping("/bootstrap")
    public BootstrapStatus bootstrapStatus() {
        return new BootstrapStatus(bootstrap.isBootstrapped());
    }

    public record BootstrapRequest(UUID firstAdministratorUserId) {}

    /**
     * VYB-0901 (F04): this endpoint has no role guard, because before it runs nobody holds
     * one — which used to make it "first authenticated caller becomes administrator". It now
     * needs, all of: a person; a matching {@code X-Bootstrap-Token} header; a configured
     * {@code BOOTSTRAP_TOKEN} (unset = disabled); no administrator already existing; and the
     * deployment not already bootstrapped. Every refusal is a 403 and none says which check failed
     * beyond "already done" versus "not authorised".
     */
    @PostMapping("/bootstrap")
    public void bootstrapTenant(@RequestBody BootstrapRequest body,
                                @RequestHeader(value = "X-Bootstrap-Token", required = false) String token,
                                @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        requireBootstrapToken(token);
        bootstrap.bootstrap(body.firstAdministratorUserId(), currentUserId(jwt));
    }

    private void requireBootstrapToken(String presented) {
        if (bootstrapToken == null || bootstrapToken.isBlank()) {
            throw new BootstrapRefusedException("Bootstrap is not enabled on this deployment");
        }
        boolean ok = presented != null && java.security.MessageDigest.isEqual(
            presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            bootstrapToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!ok) {
            throw new BootstrapRefusedException("Bootstrap is not authorised for this caller");
        }
    }

    @GetMapping
    public AppConfigService.AppConfigView current(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return config.current();
    }

    public record SetPrefix(String prefix) {}

    @PutMapping("/req-key-prefix")
    public void setReqKeyPrefix(@RequestBody SetPrefix body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setReqKeyPrefix(body.prefix());
        audit.record(currentUserId(jwt), "settings.req-key-prefix-changed", "APP_CONFIG", null,
            null, Map.of("prefix", body.prefix()));
    }

    /** VYB-0757 AC1: how many currently-stalled requirements this candidate threshold would affect, before it's applied. */
    @GetMapping("/stage-thresholds/preview")
    public long previewStageThreshold(@RequestParam String status, @RequestParam int days,
                                       @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return tasks.countAtStatusOlderThan(status, days);
    }

    @PutMapping("/stage-thresholds")
    public void setStageThresholds(@RequestBody Map<String, Integer> thresholds, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setStageStallThresholds(thresholds);
        audit.record(currentUserId(jwt), "settings.stage-thresholds-changed", "APP_CONFIG", null, null, thresholds);
    }

    public record SetDays(int days) {}

    @PutMapping("/max-external-grant-days")
    public void setMaxExternalGrantDays(@RequestBody SetDays body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setMaxExternalGrantDays(body.days());
        audit.record(currentUserId(jwt), "settings.max-external-grant-days-changed", "APP_CONFIG", null,
            null, Map.of("days", body.days()));
    }

    @PutMapping("/stale-key-age-days")
    public void setStaleKeyAgeDays(@RequestBody SetDays body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setStaleKeyAgeDays(body.days());
        audit.record(currentUserId(jwt), "settings.stale-key-age-days-changed", "APP_CONFIG", null,
            null, Map.of("days", body.days()));
    }

    @PutMapping("/key-rotation-overlap-days")
    public void setKeyRotationOverlapDays(@RequestBody SetDays body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setKeyRotationOverlapDays(body.days());
        audit.record(currentUserId(jwt), "settings.key-rotation-overlap-days-changed", "APP_CONFIG", null,
            null, Map.of("days", body.days()));
    }

    @PutMapping("/audit-retention-days")
    public void setAuditRetentionDays(@RequestBody SetDays body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setAuditRetentionDays(body.days());
        audit.record(currentUserId(jwt), "settings.audit-retention-days-changed", "APP_CONFIG", null,
            null, Map.of("days", body.days()));
    }

    /** VYB-0723: what actually exists right now — real partitions, real row counts, which are archived. */
    @GetMapping("/audit-partitions")
    public List<AuditRetentionService.PartitionInfo> auditPartitions(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return retention.listPartitions();
    }

    public record ArchiveResult(List<String> archived) {}

    /**
     * Manual trigger for the same job {@link AuditRetentionService#scheduledMaintenance}
     * runs nightly — lets an administrator see the effect of a retention-days change
     * immediately rather than waiting for 2:15am.
     */
    @PostMapping("/audit-partitions/archive")
    public ArchiveResult archiveAuditPartitions(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        retention.ensureFuturePartitions();
        List<String> archived = retention.archiveEligiblePartitions();
        if (!archived.isEmpty()) {
            audit.record(currentUserId(jwt), "settings.audit-partitions-archived", "APP_CONFIG", null,
                null, Map.of("archived", archived));
        }
        return new ArchiveResult(archived);
    }

    /**
     * VYB-0757 (session 16): the three fields the Settings screen has always shown as
     * plain read-only text — "editable via the API in a later pass" — get that pass.
     * Same guard/audit pattern as every field above.
     */
    @PutMapping("/noisy-detector-dismissal-ceiling")
    public void setNoisyDetectorDismissalCeiling(@RequestBody SetCeiling body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setNoisyDetectorDismissalCeiling(body.ceiling());
        audit.record(currentUserId(jwt), "settings.noisy-detector-dismissal-ceiling-changed", "APP_CONFIG", null,
            null, Map.of("ceiling", body.ceiling().toString()));
    }

    public record SetCeiling(java.math.BigDecimal ceiling) {}

    @PutMapping("/ai-calls-per-run-limit")
    public void setAiCallsPerRunLimit(@RequestBody SetDays body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setAiCallsPerRunLimit(body.days());
        audit.record(currentUserId(jwt), "settings.ai-calls-per-run-limit-changed", "APP_CONFIG", null,
            null, Map.of("limit", body.days()));
    }

    public record SetModel(String model) {}

    @PutMapping("/embedding-model")
    public void setEmbeddingModel(@RequestBody SetModel body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.setEmbeddingModel(body.model());
        audit.record(currentUserId(jwt), "settings.embedding-model-changed", "APP_CONFIG", null,
            null, Map.of("model", body.model()));
    }

    public record SuspendRequest(String reason) {}

    /** VYB-0731: exempted from {@code SuspensionFilter} itself so a suspended deployment can still be resumed through this same path. */
    @PostMapping("/suspend")
    public void suspend(@RequestBody SuspendRequest body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.suspend(body.reason());
        audit.record(currentUserId(jwt), "settings.suspended", "APP_CONFIG", null, null,
            Map.of("reason", String.valueOf(body.reason())));
    }

    @PostMapping("/resume")
    public void resume(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        config.resume();
        audit.record(currentUserId(jwt), "settings.resumed", "APP_CONFIG", null, null, null);
    }

    /** VYB-0732 AC1: a quick count-per-table look before committing to the full export. */
    @GetMapping("/export/summary")
    public Map<String, Long> exportSummary(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return export.summary();
    }

    @GetMapping("/export")
    public TenantExportService.Manifest export(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        audit.record(currentUserId(jwt), "settings.exported", "APP_CONFIG", null, null, null);
        return export.export();
    }

    /**
     * VYB-0732: the real full export — {@link #export()}'s same manifest, plus every
     * attachment's actual bytes fetched live from object storage and zipped alongside
     * it. Audited with the counts a diligent admin would want to see immediately,
     * rather than only discoverable by opening the zip afterward.
     */
    @GetMapping("/export/bundle")
    public org.springframework.http.ResponseEntity<byte[]> exportBundle(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        TenantExportService.ExportBundle bundle = export.exportBundle();
        audit.record(currentUserId(jwt), "settings.exported-bundle", "APP_CONFIG", null, null,
            Map.of("attachmentCount", bundle.attachmentCount(), "bundledCount", bundle.bundledCount(),
                "missingStorageKeys", bundle.missingStorageKeys()));
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.valueOf("application/zip"))
            .header("Content-Disposition", "attachment; filename=\"tenant-export.zip\"")
            .body(bundle.zipBytes());
    }

    /** VYB-0733, reframed — see {@link com.vyoog.platform.reset.TenantHardResetService}'s own note. */
    @GetMapping("/reset/preview")
    public com.vyoog.platform.reset.TenantHardResetService.ResetPreview resetPreview(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return hardReset.preview();
    }

    public record ResetRequest(String confirmationPhrase) {}
    private static final String REQUIRED_PHRASE = "WIPE ALL DATA";

    /**
     * Irreversible. Admin auth alone gates every other Settings mutation; this one
     * additionally requires the caller to have typed an exact phrase (checked here,
     * not just enforced by a frontend dialog that a direct API call could skip) —
     * proportionate to being the only endpoint in this controller that can't be
     * undone by calling its opposite.
     */
    @PostMapping("/reset")
    public void reset(@RequestBody ResetRequest body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        if (!REQUIRED_PHRASE.equals(body.confirmationPhrase())) {
            throw new IllegalArgumentException("Type \"" + REQUIRED_PHRASE + "\" exactly to confirm — nothing was wiped.");
        }
        hardReset.reset(currentUserId(jwt), jwt.getClaimAsString("email"));
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }
}
