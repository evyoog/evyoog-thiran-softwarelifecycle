package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Product;
import com.vyoog.portfolio.ProductRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0100: every application belongs to exactly one product. */
@RestController
@RequestMapping("/api/v1/products/{productId}/applications")
public class ApplicationController {

    private final ApplicationRepository applications;
    private final ProductRepository products;
    private final PrincipalGuard guard;

    public ApplicationController(ApplicationRepository applications, ProductRepository products, PrincipalGuard guard) {
        this.applications = applications;
        this.products = products;
        this.guard = guard;
    }

    public record ApplicationView(String id, String productId, String name, String description, boolean archived) {}
    public record CreateApplication(@NotBlank String name, String description) {}
    public record UpdateApplication(@NotBlank String name, String description) {}

    private static ApplicationView toView(Application a) {
        return new ApplicationView(
            a.getId().toString(), a.getProductId().toString(), a.getName(), a.getDescription(), a.isArchived());
    }

    @GetMapping
    public List<ApplicationView> list(@PathVariable UUID productId) {
        return applications.findAllByProductIdAndArchivedAtIsNull(productId).stream()
            .map(ApplicationController::toView).toList();
    }

    // VYB-0906: portfolio structure is administrator-only.
    @RequiresAccess(value = AccessRule.ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationView create(@PathVariable UUID productId, @RequestBody CreateApplication body) {
        // AC1: creating a child without a valid, unarchived parent is rejected.
        products.findById(productId)
            .filter(p -> !p.isArchived())
            .orElseThrow(() -> new IllegalArgumentException("No such product: " + productId));
        Application a = new Application(productId, body.name());
        a.setDescription(body.description());
        applications.save(a);
        return toView(a);
    }

    @RequiresAccess(value = AccessRule.ADMIN)
    @PatchMapping("/{id}")
    public ApplicationView update(@PathVariable UUID productId, @PathVariable UUID id,
                                   @RequestBody UpdateApplication body) {
        Application a = applications.findById(id).orElseThrow(NoSuchElementException::new);
        a.setName(body.name());
        a.setDescription(body.description());
        applications.save(a);
        return toView(a);
    }

    /** VYB-0790: archiving cascades to hide every capability beneath it — the same weight the frontend's own confirm dialog already treats it with; the backend hadn't matched that until now. */
    @PostMapping("/{id}/archive")
    public ApplicationView archive(@PathVariable UUID productId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        Application a = applications.findById(id).orElseThrow(NoSuchElementException::new);
        a.archive();
        applications.save(a);
        return toView(a);
    }
}
