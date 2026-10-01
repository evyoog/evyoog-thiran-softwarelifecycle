package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.portfolio.Product;
import com.vyoog.portfolio.ProductDashboardService;
import com.vyoog.portfolio.ProductRepository;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductRepository products;
    private final ProductDashboardService dashboard;
    private final PrincipalGuard guard;

    public ProductController(ProductRepository products, ProductDashboardService dashboard, PrincipalGuard guard) {
        this.products = products;
        this.dashboard = dashboard;
        this.guard = guard;
    }

    public record ProductView(
        String id, String key, String name, String vertical, String purpose,
        String ownerId, String lifecycleStatus, String mark, boolean archived) {}

    public record CreateProduct(
        @NotBlank String key, @NotBlank String name, String vertical, String purpose,
        String ownerId, String lifecycleStatus, String mark) {}

    public record UpdateProduct(
        @NotBlank String name, String vertical, String purpose,
        String ownerId, String lifecycleStatus, String mark) {}

    private static ProductView toView(Product p) {
        return new ProductView(
            p.getId().toString(), p.getKey(), p.getName(), p.getVertical(), p.getPurpose(),
            p.getOwnerId() == null ? null : p.getOwnerId().toString(),
            p.getLifecycleStatus(), p.getMark(), p.isArchived());
    }

    @GetMapping
    public List<ProductView> list() {
        // VYB-0101 AC2: an archived item is excluded from default listings.
        return products.findAllByArchivedAtIsNullOrderByNameAsc().stream().map(ProductController::toView).toList();
    }

    /** VYB-0788: the card-grid dashboard's real per-product/per-application figures. */
    @GetMapping("/dashboard")
    public ProductDashboardService.Dashboard dashboard() {
        return dashboard.dashboard();
    }

    /**
     * The capability cards on the app detail screen.
     *
     * <p>Lives here rather than on the capability controller because it is the dashboard's
     * arithmetic, not the capability's own data — same query shape, same definition of a
     * gap, so the cards cannot disagree with the app header sitting above them.
     */
    @GetMapping("/applications/{applicationId}/capability-summary")
    public List<ProductDashboardService.CapabilitySummary> capabilitySummary(@PathVariable UUID applicationId) {
        return dashboard.capabilitiesOf(applicationId);
    }

    /** VYB-0788: "Portfolio report" — the exact same figures the dashboard renders, as CSV. */
    @GetMapping("/dashboard/report.csv")
    public ResponseEntity<byte[]> dashboardReport() {
        byte[] csv = dashboard.dashboardCsv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header("Content-Disposition", "attachment; filename=\"portfolio-report.csv\"")
            .body(csv);
    }

    // VYB-0906: portfolio structure is administrator-only (nearest matrix column: Admin).
    @RequiresAccess(value = AccessRule.ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@RequestBody CreateProduct body) {
        Product p = new Product(body.key(), body.name());
        applyOptionalFields(p, body.vertical(), body.purpose(), body.ownerId(), body.lifecycleStatus(), body.mark());
        products.save(p);
        return toView(p);
    }

    @RequiresAccess(value = AccessRule.ADMIN)
    @PatchMapping("/{id}")
    public ProductView update(@PathVariable UUID id, @RequestBody UpdateProduct body) {
        Product p = products.findById(id).orElseThrow(NoSuchElementException::new);
        p.setName(body.name());
        applyOptionalFields(p, body.vertical(), body.purpose(), body.ownerId(), body.lifecycleStatus(), body.mark());
        products.save(p);
        return toView(p);
    }

    private static void applyOptionalFields(
        Product p, String vertical, String purpose, String ownerId, String lifecycleStatus, String mark) {
        p.setVertical(vertical);
        p.setPurpose(purpose);
        p.setOwnerId(ownerId == null || ownerId.isBlank() ? null : UUID.fromString(ownerId));
        if (lifecycleStatus != null && !lifecycleStatus.isBlank()) p.setLifecycleStatus(lifecycleStatus);
        if (mark != null && !mark.isBlank()) p.setMark(mark);
    }

    /** VYB-0790: archiving a product cascades to hide every application/capability beneath it. */
    @PostMapping("/{id}/archive")
    public ProductView archive(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        Product p = products.findById(id).orElseThrow(NoSuchElementException::new);
        p.archive();
        products.save(p);
        return toView(p);
    }
}
