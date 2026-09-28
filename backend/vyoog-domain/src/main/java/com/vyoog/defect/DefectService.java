package com.vyoog.defect;

import com.vyoog.notify.NotificationService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0320–0323. {@code developer_id} is the only routing column the {@code defect}
 * table actually has (V001__baseline.sql) — the tester side of VYB-0322's routing is
 * a notification only, never a stored column, since there's nowhere to store it.
 */
@Service
public class DefectService {

    private final DefectRepository defects;
    private final DefectKeyAllocator keys;
    private final RequirementRepository requirements;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    public DefectService(DefectRepository defects, DefectKeyAllocator keys, RequirementRepository requirements,
                          AuditService audit, NotificationService notifications, JdbcTemplate jdbc) {
        this.defects = defects;
        this.keys = keys;
        this.requirements = requirements;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    /** VYB-0320 AC1/VYB-0322: raising never blocks on an untraced requirement or unknown actors. */
    @Transactional
    public Defect raise(String title, DefectSeverity severity, UUID requirementId, FoundIn foundIn, UUID actor) {
        UUID developerId = null;
        UUID testerId = null;
        if (requirementId != null) {
            Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
            developerId = r.getDeveloperId();
            testerId = r.getTesterId();
        }

        Defect d = defects.save(new Defect(keys.next(), title, severity, requirementId, foundIn, developerId, testerId));

        audit.record(actor, "defect.raised", "DEFECT", d.getId(), null,
            Map.of("key", d.getKey(), "requirementId", requirementId == null ? "" : requirementId.toString(),
                   "developerId", developerId == null ? "" : developerId.toString(),
                   "testerId", testerId == null ? "" : testerId.toString()));

        if (developerId != null) {
            notifications.notify(developerId, "warn", "defect-routed-developer", "Defect " + d.getKey() + " routed to you",
                title, "/defects/" + d.getId());
        }
        if (testerId != null) {
            notifications.notify(testerId, "warn", "defect-routed-tester", "Defect " + d.getKey() + " on a requirement you verified",
                title, "/defects/" + d.getId());
        }
        return d;
    }

    /** VYB-0321 AC1: mandatory before closure — this is where a defect gets one, at any state. */
    @Transactional
    public Defect classify(UUID id, RootCause rootCause, UUID actor) {
        Defect d = defects.findById(id).orElseThrow(NoSuchElementException::new);
        d.classify(rootCause);
        defects.save(d);
        audit.record(actor, "defect.classified", "DEFECT", d.getId(), null, Map.of("rootCause", rootCause.name()));
        return d;
    }

    @Transactional
    public Defect close(UUID id, UUID actor) {
        Defect d = defects.findById(id).orElseThrow(NoSuchElementException::new);
        d.close();
        defects.save(d);
        audit.record(actor, "defect.closed", "DEFECT", d.getId(), null, Map.of());
        return d;
    }

    public record RootCauseSplit(String rootCause, long count) {}

    /** VYB-0323: computed from real classified defects, filterable by capability. */
    public List<RootCauseSplit> rootCauseSplit(UUID capabilityId) {
        String sql = """
            SELECT d.root_cause, count(*) AS n
            FROM defect d
            LEFT JOIN requirement r ON r.id = d.requirement_id
            WHERE d.root_cause IS NOT NULL
            """ + (capabilityId != null ? " AND r.capability_id = ?" : "") + """

            GROUP BY d.root_cause
            ORDER BY n DESC
            """;
        return capabilityId != null
            ? jdbc.query(sql, (rs, n) -> new RootCauseSplit(rs.getString("root_cause"), rs.getLong("n")), capabilityId)
            : jdbc.query(sql, (rs, n) -> new RootCauseSplit(rs.getString("root_cause"), rs.getLong("n")));
    }
}
