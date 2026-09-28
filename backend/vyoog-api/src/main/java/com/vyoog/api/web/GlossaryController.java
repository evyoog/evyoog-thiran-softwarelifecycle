package com.vyoog.api.web;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.portfolio.GlossaryService;
import com.vyoog.portfolio.GlossaryTerm;
import com.vyoog.portfolio.GlossaryTermRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0102/0103: glossary terms, per-application usage, and conflict detection. */
@RestController
@RequestMapping("/api/v1/glossary")
public class GlossaryController {

    private final GlossaryTermRepository terms;
    private final GlossaryService service;
    private final UserProvisioningService provisioning;

    public GlossaryController(GlossaryTermRepository terms, GlossaryService service,
                               UserProvisioningService provisioning) {
        this.terms = terms;
        this.service = service;
        this.provisioning = provisioning;
    }

    public record TermView(String id, String term, String definition, String ownerId) {}
    public record CreateTerm(@NotBlank String term, @NotBlank String definition) {}
    public record RecordUsage(String definition) {}
    public record VariantView(String definition, List<String> applicationIds) {}
    public record ConflictView(String termId, String term, List<VariantView> variants) {}

    private static TermView toView(GlossaryTerm t) {
        return new TermView(t.getId().toString(), t.getTerm(), t.getDefinition(),
            t.getOwnerId() == null ? null : t.getOwnerId().toString());
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    @GetMapping
    public List<TermView> list() {
        return terms.findAllByOrderByTermAsc().stream().map(GlossaryController::toView).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TermView create(@RequestBody CreateTerm body, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.createTerm(body.term(), body.definition(), currentUserId(jwt)));
    }

    @PutMapping("/{termId}/usage/{applicationId}")
    public void recordUsage(@PathVariable UUID termId, @PathVariable UUID applicationId, @RequestBody RecordUsage body) {
        service.recordUsage(termId, applicationId, body.definition());
    }

    @GetMapping("/conflicts")
    public List<ConflictView> conflicts() {
        return service.findConflicts().stream()
            .map(c -> new ConflictView(c.termId().toString(), c.term(),
                c.variants().stream()
                    .map(v -> new VariantView(v.definition(), v.applicationIds().stream().map(UUID::toString).toList()))
                    .toList()))
            .toList();
    }
}
