package com.vyoog.baseline;

import com.vyoog.platform.audit.AuditService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0470/0472. Step-up (VYB-0471) is checked by the controller before {@link
 * #freeze} is ever called — same layering as {@code ReviewService#sign} in Phase 2,
 * for the same reason: this domain class shouldn't need to know what a JWT is.
 */
@Service
public class BaselineService {

    private final BaselineRepository baselines;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BaselineService(BaselineRepository baselines, JdbcTemplate jdbc, AuditService audit) {
        this.baselines = baselines;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** VYB-0470 AC1/AC2/AC3: revisions frozen now, gap count recorded now, actor recorded. */
    @Transactional
    public Baseline freeze(String name, UUID releaseId, List<UUID> requirementIds, UUID actor) {
        if (requirementIds.isEmpty()) {
            throw new IllegalArgumentException("A baseline needs at least one requirement in scope");
        }
        String placeholders = String.join(",", requirementIds.stream().map(i -> "?").toList());
        Integer gaps = jdbc.queryForObject(
            ("SELECT count(*) FROM finding f WHERE f.state = 'OPEN' AND f.object_type = 'REQUIREMENT' "
                + "AND f.object_id IN (%s)").formatted(placeholders),
            Integer.class, requirementIds.toArray());

        // saveAndFlush, for the reason spelled out in BriefService#generate: the
        // baseline_item rows below are written with JdbcTemplate, which does not wait for
        // Hibernate's flush, so a deferred parent INSERT leaves them violating
        // baseline_item_baseline_id_fkey. A baseline always has at least one item, so
        // unlike the brief this had no empty-scope path to hide behind.
        Baseline baseline = baselines.saveAndFlush(new Baseline(name, releaseId, actor, gaps == null ? 0 : gaps));

        for (UUID reqId : requirementIds) {
            Integer revision = jdbc.queryForObject(
                "SELECT revision FROM requirement WHERE id = ?", Integer.class, reqId);
            if (revision == null) throw new NoSuchElementException("No such requirement: " + reqId);
            jdbc.update("INSERT INTO baseline_item (baseline_id, requirement_id, revision) VALUES (?,?,?)",
                baseline.getId(), reqId, revision);
        }

        audit.record(actor, "baseline.frozen", "BASELINE", baseline.getId(), null,
            Map.of("name", name, "items", requirementIds.size(), "gapsAtFreeze", baseline.getGapsAtFreeze()));
        return baseline;
    }

    public List<Baseline> list() {
        return baselines.findAllByOrderByFrozenAtDesc();
    }

    public record BaselineItemView(String requirementId, String key, int revision) {}

    public List<BaselineItemView> items(UUID baselineId) {
        return jdbc.query("""
            SELECT bi.requirement_id, r.key, bi.revision FROM baseline_item bi
            JOIN requirement r ON r.id = bi.requirement_id
            WHERE bi.baseline_id = ? ORDER BY r.key
            """,
            (rs, n) -> new BaselineItemView(rs.getString("requirement_id"), rs.getString("key"), rs.getInt("revision")),
            baselineId);
    }

    public record ChangedItem(String requirementId, String key, int fromRevision, int toRevision) {}
    public record BaselineDiff(
        List<BaselineItemView> added, List<BaselineItemView> removed, List<ChangedItem> changed) {}

    /** VYB-0472: added, removed and changed listed separately; changed shows both revisions. */
    public BaselineDiff diff(UUID fromId, UUID toId) {
        Map<UUID, BaselineItemView> from = new HashMap<>();
        for (BaselineItemView v : items(fromId)) from.put(UUID.fromString(v.requirementId()), v);
        Map<UUID, BaselineItemView> to = new HashMap<>();
        for (BaselineItemView v : items(toId)) to.put(UUID.fromString(v.requirementId()), v);

        List<BaselineItemView> added = to.entrySet().stream()
            .filter(e -> !from.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        List<BaselineItemView> removed = from.entrySet().stream()
            .filter(e -> !to.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        List<ChangedItem> changed = from.entrySet().stream()
            .filter(e -> to.containsKey(e.getKey()) && to.get(e.getKey()).revision() != e.getValue().revision())
            .map(e -> new ChangedItem(e.getKey().toString(), e.getValue().key(),
                e.getValue().revision(), to.get(e.getKey()).revision()))
            .toList();

        return new BaselineDiff(added, removed, changed);
    }
}
