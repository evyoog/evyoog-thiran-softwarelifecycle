package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.baseline.Variant;
import com.vyoog.baseline.VariantService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** VYB-0473. */
@RestController
@RequestMapping("/api/v1/variants")
public class VariantController {

    private final VariantService service;

    public VariantController(VariantService service) {
        this.service = service;
    }

    public record CreateVariant(@NotBlank String name) {}
    public record VariantView(String id, String name) {}

    private static VariantView toView(Variant v) {
        return new VariantView(v.getId().toString(), v.getName());
    }

    @GetMapping
    public List<VariantView> list() {
        return service.list().stream().map(VariantController::toView).toList();
    }

    // VYB-0906: variants and applicability are authored content.
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VariantView create(@RequestBody CreateVariant body) {
        return toView(service.create(body.name()));
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/{variantId}/applicability/{requirementId}")
    public void markApplies(@PathVariable UUID variantId, @PathVariable UUID requirementId) {
        service.markApplies(variantId, requirementId);
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @DeleteMapping("/{variantId}/applicability/{requirementId}")
    public void clear(@PathVariable UUID variantId, @PathVariable UUID requirementId) {
        service.clearApplicability(variantId, requirementId);
    }

    public record MatrixRowView(String requirementId, String key, List<String> variantIds) {}
    public record MatrixRequest(@NotEmpty List<String> requirementIds) {}

    /** VYB-0519: a requirement missing from every list here applies to all editions. */
    // VYB-0906: computes a matrix, stores nothing.
    @RequiresAccess(value = AccessRule.PERSON)
    @PostMapping("/matrix")
    public List<MatrixRowView> matrix(@RequestBody MatrixRequest body) {
        List<UUID> ids = body.requirementIds().stream().map(UUID::fromString).toList();
        return service.matrix(ids).stream()
            .map(r -> new MatrixRowView(r.requirementId(), r.key(), r.variantIds())).toList();
    }
}
