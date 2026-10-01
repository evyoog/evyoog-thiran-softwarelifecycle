package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseQueryService;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0363: drafting a test case as a proposal, human-facing — CI ingestion (VYB-0310) is a separate path. */
@RestController
@RequestMapping("/api/v1/test-cases")
public class TestCaseController {

    private final TestCaseService service;
    private final TestCaseQueryService queryService;
    private final UserProvisioningService provisioning;

    public TestCaseController(TestCaseService service, TestCaseQueryService queryService,
                               UserProvisioningService provisioning) {
        this.service = service;
        this.queryService = queryService;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    private static TestCase.Category category(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return TestCase.Category.valueOf(raw);
    }

    public record DraftTestCase(
        @NotBlank String title, String description, String category, @NotNull UUID requirementId) {}

    public record TestCaseView(
        String id, String key, String title, String description, String category, String status, String requirementId) {}

    /**
     * VYB-0824/0827: the only persistence path for a test case, manual or AI-accepted —
     * the frontend calls this identically either way, prefilling the body from a
     * suggestion (possibly edited first, {@code category} carried through unedited) when
     * it came from {@code POST /requirements/{id}/test-case-suggestions}. A manual entry
     * sends no category, same as every row created before VYB-0827 existed.
     */
    // VYB-0906: test cases are QA work (matrix: Verify).
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseView draft(@RequestBody DraftTestCase body, @AuthenticationPrincipal Jwt jwt) {
        TestCase tc = service.draft(body.title(), body.description(), category(body.category()),
            body.requirementId(), currentUserId(jwt));
        return new TestCaseView(tc.getId().toString(), tc.getKey(), tc.getTitle(), tc.getDescription(),
            tc.getCategory() == null ? null : tc.getCategory().name(), tc.getStatus().name(), body.requirementId().toString());
    }

    public record TestCaseListItemView(
        String id, String key, String title, String description, String category, String status,
        String requirementId, String requirementKey, String requirementTitle) {}

    /** VYB-0825: every test case that already exists, browsable — nothing before this listed them, only counts and requirement rows. */
    @GetMapping
    public Page<TestCaseListItemView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 25) Pageable pageable) {
        return queryService.list(q, status, pageable).map(r -> new TestCaseListItemView(
            r.id().toString(), r.key(), r.title(), r.description(), r.category(), r.status(),
            r.requirementId() == null ? null : r.requirementId().toString(), r.requirementKey(), r.requirementTitle()));
    }

    public record RequirementSummaryView(
        String requirementId, String requirementKey, String requirementTitle,
        long individualCount, long dependencyCount, long otherCount, long totalCount) {}

    /**
     * VYB-0827: one row per requirement that has at least one test case, with counts by
     * category — the "Test cases" tab's top level. {@code q} matches the requirement's
     * own key/title or any of its test cases'.
     */
    @GetMapping("/by-requirement")
    public Page<RequirementSummaryView> byRequirement(
            @RequestParam(required = false) String q, @PageableDefault(size = 25) Pageable pageable) {
        return queryService.listRequirementsWithTestCases(q, pageable).map(r -> new RequirementSummaryView(
            r.requirementId().toString(), r.requirementKey(), r.requirementTitle(),
            r.individualCount(), r.dependencyCount(), r.otherCount(), r.totalCount()));
    }

    /** VYB-0827: one requirement's test cases, expanded on click — INDIVIDUAL/DEPENDENCY/uncategorized, split client-side by `category`. */
    @GetMapping("/by-requirement/{requirementId}")
    public List<TestCaseView> forRequirement(@PathVariable UUID requirementId) {
        return queryService.listForRequirement(requirementId).stream()
            .map(r -> new TestCaseView(r.id().toString(), r.key(), r.title(), r.description(),
                r.category(), r.status(), requirementId.toString()))
            .toList();
    }

    public record UpdateTestCase(@NotBlank String title, String description, String category) {}

    /**
     * VYB-0828: correcting or expanding a test case already saved — the counterpart to
     * {@link #draft}. Only title/description/category change; {@code key}, {@code
     * status} and the VERIFIES link are untouched, so the response omits {@code
     * requirementId} — the caller already knows which requirement's test case this is,
     * and nothing here can change that.
     */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PatchMapping("/{id}")
    public TestCaseView update(@PathVariable UUID id, @RequestBody UpdateTestCase body, @AuthenticationPrincipal Jwt jwt) {
        TestCase tc = service.update(id, body.title(), body.description(), category(body.category()), currentUserId(jwt));
        return new TestCaseView(tc.getId().toString(), tc.getKey(), tc.getTitle(), tc.getDescription(),
            tc.getCategory() == null ? null : tc.getCategory().name(), tc.getStatus().name(), null);
    }
}
