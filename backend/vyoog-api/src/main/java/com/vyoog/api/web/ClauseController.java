package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.clause.Clause;
import com.vyoog.clause.ClauseRepository;
import com.vyoog.clause.ClauseService;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0611: control clauses. Nothing ingests these from a real compliance-framework source: they are entered in the register
 * (VYB-0947, D38), which also maps each to the requirements that satisfy it and shows how far each is evidenced.
 */
@RestController
@RequestMapping("/api/v1/clauses")
public class ClauseController {

    private final ClauseRepository clauses;
    private final ClauseService service;
    private final UserProvisioningService provisioning;

    public ClauseController(ClauseRepository clauses, ClauseService service, UserProvisioningService provisioning) {
        this.clauses = clauses;
        this.service = service;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record ClauseView(String id, String standard, String section, String text) {}
    public record CreateClause(@NotBlank String standard, String section, @NotBlank String text) {}
    public record UpdateClause(@NotBlank String standard, String section, @NotBlank String text) {}

    private static ClauseView toView(Clause c) {
        return new ClauseView(c.getId().toString(), c.getStandard(), c.getSection(), c.getText());
    }

    @GetMapping
    public List<ClauseView> list() {
        return clauses.findAll().stream().map(ClauseController::toView).toList();
    }

    // VYB-0906: the compliance clause register is administrator-managed.
    @RequiresAccess(value = AccessRule.ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClauseView create(@RequestBody @jakarta.validation.Valid CreateClause body, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.create(body.standard(), body.section(), body.text(), currentUserId(jwt)));
    }

    @GetMapping("/{id}")
    public ClauseView get(@PathVariable UUID id) {
        return toView(clauses.findById(id).orElseThrow(NoSuchElementException::new));
    }

    // ------------------------------------------------------------------ VYB-0947

    public record ClauseRegisterRow(String id, String standard, String section, String text, int mappedRequirements,
                                    int evidencedRequirements, String coverage) {}
    public record ClauseMappedTest(String id, String key, String title, String result) {}
    public record ClauseMappedRequirement(String id, String key, String title, String status, boolean counts, boolean evidenced,
                                          boolean staleEvidence, String replacedByKey, List<ClauseMappedTest> tests) {}
    public record ClauseMappingView(String id, String standard, String section, String text, String coverage,
                                    List<ClauseMappedRequirement> requirements) {}

    /** Every clause with how many requirements count toward it and how many of those have passing evidence. Read by any signed-in person. */
    @GetMapping("/register")
    public List<ClauseRegisterRow> register() {
        return service.register().stream().map(r -> new ClauseRegisterRow(r.id().toString(), r.standard(), r.section(), r.text(),
            r.mappedRequirements(), r.evidencedRequirements(), r.coverage())).toList();
    }

    /** One clause with its mapped requirements, their evidence and their tests. */
    @GetMapping("/{id}/mapping")
    public ClauseMappingView mapping(@PathVariable UUID id) {
        var m = service.mappingOf(id);
        return new ClauseMappingView(m.id().toString(), m.standard(), m.section(), m.text(), m.coverage(), m.requirements().stream()
            .map(r -> new ClauseMappedRequirement(r.id().toString(), r.key(), r.title(), r.status(), r.counts(), r.evidenced(),
                r.staleEvidence(), r.replacedByKey(), r.tests().stream()
                    .map(t -> new ClauseMappedTest(t.id().toString(), t.key(), t.title(), t.result())).toList())).toList());
    }

    @RequiresAccess(value = AccessRule.ADMIN)
    @PutMapping("/{id}")
    public ClauseView update(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid UpdateClause body, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.update(id, body.standard(), body.section(), body.text(), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.ADMIN)
    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, currentUserId(jwt));
    }

    /** Whoever may create trace links may map a requirement to a clause (it is a SATISFIES link). */
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/{id}/requirements/{requirementId}")
    public void map(@PathVariable UUID id, @PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        service.map(id, requirementId, currentUserId(jwt));
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @DeleteMapping("/{id}/requirements/{requirementId}")
    public void unmap(@PathVariable UUID id, @PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        service.unmap(id, requirementId, currentUserId(jwt));
    }
}
