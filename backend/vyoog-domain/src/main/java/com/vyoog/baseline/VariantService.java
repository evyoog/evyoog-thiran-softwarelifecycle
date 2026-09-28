package com.vyoog.baseline;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0473: {@code variant_applicability} has no surrogate key, so it's plain SQL
 * here, same reasoning as everywhere else in this codebase a join table has none.
 * The absence of a row is the default — "applies to all" (AC2) — and this never
 * writes {@code applies = false}: an explicit row always narrows scope to exactly
 * the variants named, never records an exclusion from an implicit "all" (a real but
 * intentionally unexercised use of that column — see BUILD-REGISTER.md).
 */
@Service
public class VariantService {

    private final VariantRepository variants;
    private final JdbcTemplate jdbc;

    public VariantService(VariantRepository variants, JdbcTemplate jdbc) {
        this.variants = variants;
        this.jdbc = jdbc;
    }

    public Variant create(String name) {
        return variants.save(new Variant(name));
    }

    public List<Variant> list() {
        return variants.findAll();
    }

    /** VYB-0473 AC1: a requirement may apply to several editions — each is its own row. */
    @Transactional
    public void markApplies(UUID variantId, UUID requirementId) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM variant_applicability WHERE variant_id = ? AND requirement_id = ?)",
            Boolean.class, variantId, requirementId));
        if (!exists) {
            jdbc.update(
                "INSERT INTO variant_applicability (variant_id, requirement_id, applies) VALUES (?,?,true)",
                variantId, requirementId);
        }
    }

    /** Back to the implicit default — applies to all editions. */
    @Transactional
    public void clearApplicability(UUID variantId, UUID requirementId) {
        jdbc.update("DELETE FROM variant_applicability WHERE variant_id = ? AND requirement_id = ?",
            variantId, requirementId);
    }

    public record MatrixRow(String requirementId, String key, List<String> variantIds) {}

    /** VYB-0519: the matrix the frontend renders — an empty {@code variantIds} means applies-to-all. */
    public List<MatrixRow> matrix(List<UUID> requirementIds) {
        if (requirementIds.isEmpty()) return List.of();
        String placeholders = String.join(",", requirementIds.stream().map(i -> "?").toList());
        List<Object> params = new java.util.ArrayList<>(requirementIds);
        var explicit = jdbc.query(("""
            SELECT r.id AS requirement_id, r.key, array_agg(va.variant_id::text) AS variant_ids
            FROM requirement r
            LEFT JOIN variant_applicability va ON va.requirement_id = r.id AND va.applies
            WHERE r.id IN (%s)
            GROUP BY r.id, r.key
            ORDER BY r.key
            """).formatted(placeholders),
            (rs, n) -> {
                java.sql.Array arr = rs.getArray("variant_ids");
                List<String> ids = new java.util.ArrayList<>();
                if (arr != null) {
                    for (Object o : (Object[]) arr.getArray()) if (o != null) ids.add((String) o);
                }
                return new MatrixRow(rs.getString("requirement_id"), rs.getString("key"), ids);
            },
            params.toArray());
        return explicit;
    }
}
