package com.vyoog.api.web;

import com.vyoog.evidence.RequirementPassRateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** VYB-0927: read-only figures for the Quality screen. Open to any signed-in person, like the other quality reads. */
@RestController
@RequestMapping("/api/v1/quality")
public class QualityController {

    private final RequirementPassRateService passRates;

    public QualityController(RequirementPassRateService passRates) {
        this.passRates = passRates;
    }

    /** {@code passRate} is null (not zero) when no test case has a result at the requirement's current revision. */
    public record PassRateView(String requirementId, String key, String title, String status, int revision, int cases,
                                int passed, int failed, int stale, int notRun, Double passRate, String lastResultAt) {}

    @GetMapping("/pass-rates")
    public Page<PassRateView> passRates(@RequestParam(required = false) String q, @PageableDefault(size = 25) Pageable pageable) {
        return passRates.list(q, pageable).map(p -> new PassRateView(p.requirementId().toString(), p.key(), p.title(), p.status(),
            p.revision(), p.cases(), p.passed(), p.failed(), p.stale(), p.notRun(), p.passRate(),
            p.lastResultAt() == null ? null : p.lastResultAt().toString()));
    }
}
