package com.vyoog.api.web;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.release.ReadinessSnapshotService.Snapshot;
import com.vyoog.report.TrendReportService;
import com.vyoog.report.TrendService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0956 (F24, D43): trends for defects, test pass rate and release readiness, and the same as an xlsx or PDF file. Reads only,
 * counts and rates only (nothing per person), open to anyone signed in; an export is audited because it leaves the system.
 */
@RestController
@RequestMapping("/api/v1/trends")
public class TrendController {

    private final TrendService trends;
    private final TrendReportService reports;
    private final UserProvisioningService provisioning;

    public TrendController(TrendService trends, TrendReportService reports, UserProvisioningService provisioning) {
        this.trends = trends;
        this.reports = reports;
        this.provisioning = provisioning;
    }

    public record TrendDefectWeek(String weekStart, int opened, int closed, int open) {}
    /** {@code rate} is absent for a week with no results: that is "no results", not zero. */
    public record TrendPassWeek(String weekStart, int passed, int failed, Double rate) {}
    public record TrendReadinessDay(String day, int committed, int approved, int verified, int blocked, int criticalGaps) {}
    public record TrendReadiness(String releaseId, String releaseName, String state, List<TrendReadinessDay> history, TrendReadinessDay current) {}
    public record TrendsView(String generatedAt, int days, String productId, String productName, String applicationId, String applicationName,
                             List<TrendDefectWeek> defects, List<TrendPassWeek> passRate, TrendReadiness readiness) {}

    private static String str(UUID id) { return id == null ? null : id.toString(); }

    private static TrendReadinessDay day(Snapshot s) {
        return new TrendReadinessDay(s.takenOn().toString(), s.committed(), s.approved(), s.verified(), s.blocked(), s.criticalGaps());
    }

    @GetMapping
    public TrendsView get(@RequestParam(required = false) UUID productId, @RequestParam(required = false) UUID applicationId,
                          @RequestParam(defaultValue = "90") int days, @RequestParam(required = false) UUID releaseId) {
        var t = trends.trends(productId, applicationId, days, releaseId);
        var r = t.readiness();
        return new TrendsView(t.generatedAt().toString(), t.days(), str(t.productId()), t.productName(), str(t.applicationId()), t.applicationName(),
            t.defects().stream().map(d -> new TrendDefectWeek(d.weekStart().toString(), d.opened(), d.closed(), d.open())).toList(),
            t.passRate().stream().map(p -> new TrendPassWeek(p.weekStart().toString(), p.passed(), p.failed(), p.rate())).toList(),
            r == null ? null : new TrendReadiness(str(r.releaseId()), r.releaseName(), r.state(), r.history().stream().map(TrendController::day).toList(), day(r.current())));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String format, @RequestParam(required = false) UUID productId,
                                         @RequestParam(required = false) UUID applicationId, @RequestParam(defaultValue = "90") int days,
                                         @RequestParam(required = false) UUID releaseId, @AuthenticationPrincipal Jwt jwt) {
        var user = provisioning.upsert(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
        var file = reports.export(TrendReportService.Format.of(format), productId, applicationId, days, releaseId, user.getId(), user.getDisplayName());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, file.contentType())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
            .body(file.bytes());
    }
}
