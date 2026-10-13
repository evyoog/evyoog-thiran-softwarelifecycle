package com.vyoog.release;

import com.vyoog.platform.audit.AuditService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0956 (F24, D43): the history of release readiness. Readiness is derived on every read and nothing kept it, so a nightly job
 * records, for each release that is still being worked (and the day after one is released, so its last figures are kept), the same
 * five numbers the release screen and the gates use. One row per release per day; running the job twice in a day replaces that
 * day's row. A day with no row is a day with no data, and the trend shows it as a gap, never a zero.
 */
@Service
public class ReadinessSnapshotService {

    /** One day's readiness of one release. {@code committed} is the size of its scope. */
    public record Snapshot(LocalDate takenOn, int committed, int approved, int verified, int blocked, int criticalGaps) {}

    private final JdbcTemplate jdbc;
    private final ReleaseService releases;
    private final AuditService audit;

    public ReadinessSnapshotService(JdbcTemplate jdbc, ReleaseService releases, AuditService audit) {
        this.jdbc = jdbc;
        this.releases = releases;
        this.audit = audit;
    }

    /** Readiness as it is now, in the shape of a snapshot; stored nowhere. */
    public Snapshot measure(UUID releaseId, LocalDate day) {
        List<UUID> scope = releases.scope(releaseId);
        if (scope.isEmpty()) return new Snapshot(day, 0, 0, 0, 0, 0);
        var r = releases.readiness(releaseId);
        String placeholders = String.join(",", scope.stream().map(i -> "?").toList());
        Integer approved = jdbc.queryForObject("SELECT count(*) FROM requirement WHERE status = 'APPROVED' AND id IN (" + placeholders + ")",
            Integer.class, scope.toArray());
        return new Snapshot(day, r.committed(), approved == null ? 0 : approved, r.verified(), releases.blocked(releaseId).size(), r.criticalOpenGaps());
    }

    /**
     * Records today's row for every release still being worked, and for any released in the last day. Returns how many it wrote.
     * One audit event for the run, not one per release: the rows are measurements, and the event says when and how many.
     */
    @Transactional
    public int takeAll(LocalDate day) {
        Instant since = day.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<UUID> due = jdbc.queryForList("""
            SELECT r.id FROM release r
             WHERE r.state <> 'RELEASED'
                OR EXISTS (SELECT 1 FROM release_transition t WHERE t.release_id = r.id AND t.to_state = 'RELEASED' AND t.changed_at >= ?)
            """, UUID.class, java.sql.Timestamp.from(since));
        for (UUID id : due) {
            Snapshot s = measure(id, day);
            jdbc.update("""
                INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (release_id, taken_on) DO UPDATE
                   SET committed = EXCLUDED.committed, approved = EXCLUDED.approved, verified = EXCLUDED.verified,
                       blocked = EXCLUDED.blocked, critical_gaps = EXCLUDED.critical_gaps, taken_at = now()
                """, id, day, s.committed(), s.approved(), s.verified(), s.blocked(), s.criticalGaps());
        }
        audit.recordSystem("report.readiness_snapshot_taken", "RELEASE_READINESS", null, Map.of("takenOn", day.toString(), "releases", due.size()));
        return due.size();
    }

    /** The recorded days of one release from {@code from} on, oldest first. */
    public List<Snapshot> history(UUID releaseId, LocalDate from) {
        return jdbc.query("""
            SELECT taken_on, committed, approved, verified, blocked, critical_gaps FROM release_readiness_snapshot
             WHERE release_id = ? AND taken_on >= ? ORDER BY taken_on
            """, (rs, n) -> new Snapshot(rs.getDate("taken_on").toLocalDate(), rs.getInt("committed"), rs.getInt("approved"),
            rs.getInt("verified"), rs.getInt("blocked"), rs.getInt("critical_gaps")), releaseId, java.sql.Date.valueOf(from));
    }
}
