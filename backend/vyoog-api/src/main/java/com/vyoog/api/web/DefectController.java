package com.vyoog.api.web;

import com.vyoog.defect.Defect;
import com.vyoog.defect.DefectRepository;
import com.vyoog.defect.DefectSeverity;
import com.vyoog.defect.DefectService;
import com.vyoog.defect.DefectState;
import com.vyoog.defect.FoundIn;
import com.vyoog.defect.RootCause;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0320–0323/0364/0365. */
@RestController
@RequestMapping("/api/v1")
public class DefectController {

    private final DefectRepository defects;
    private final DefectService service;
    private final UserProvisioningService provisioning;

    public DefectController(DefectRepository defects, DefectService service, UserProvisioningService provisioning) {
        this.defects = defects;
        this.service = service;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record DefectView(
        String id, String key, String title, String severity, String requirementId, boolean untraced,
        String foundIn, String rootCause, String developerId, String testerId, String state, String raisedAt) {}

    public record RaiseDefect(
        @NotBlank String title, @NotBlank String severity, String requirementId, @NotBlank String foundIn) {}
    public record ClassifyDefect(@NotBlank String rootCause) {}
    public record RootCauseSplitView(String rootCause, long count) {}

    private static DefectView toView(Defect d) {
        return new DefectView(
            d.getId().toString(), d.getKey(), d.getTitle(), d.getSeverity().name(),
            d.getRequirementId() == null ? null : d.getRequirementId().toString(), d.isUntraced(),
            d.getFoundIn().name(), d.getRootCause() == null ? null : d.getRootCause().name(),
            d.getDeveloperId() == null ? null : d.getDeveloperId().toString(),
            d.getTesterId() == null ? null : d.getTesterId().toString(),
            d.getState().name(), d.getRaisedAt().toString());
    }

    @GetMapping("/defects")
    public Page<DefectView> list(@RequestParam(defaultValue = "OPEN") DefectState state,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return defects.findAllByState(state, PageRequest.of(page, size)).map(DefectController::toView);
    }

    /** VYB-0365: raising from the requirement detail panel means requirementId always arrives set. */
    @PostMapping("/defects")
    @ResponseStatus(HttpStatus.CREATED)
    public DefectView raise(@RequestBody RaiseDefect body, @AuthenticationPrincipal Jwt jwt) {
        Defect d = service.raise(body.title(), DefectSeverity.valueOf(body.severity()),
            body.requirementId() == null ? null : UUID.fromString(body.requirementId()),
            FoundIn.valueOf(body.foundIn()), currentUserId(jwt));
        return toView(d);
    }

    @PostMapping("/defects/{id}/classify")
    public DefectView classify(@PathVariable UUID id, @RequestBody ClassifyDefect body,
                                @AuthenticationPrincipal Jwt jwt) {
        return toView(service.classify(id, RootCause.valueOf(body.rootCause()), currentUserId(jwt)));
    }

    @PostMapping("/defects/{id}/close")
    public DefectView close(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.close(id, currentUserId(jwt)));
    }

    /** VYB-0323/0364 AC2: the requirement-versus-coding split, filterable by capability. */
    @GetMapping("/defects/root-cause-split")
    public List<RootCauseSplitView> rootCauseSplit(@RequestParam(required = false) UUID capabilityId) {
        return service.rootCauseSplit(capabilityId).stream()
            .map(s -> new RootCauseSplitView(s.rootCause(), s.count()))
            .toList();
    }
}
