package com.vyoog.changerequest;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceObjectType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0390–0392. {@code change_request_requirement} has no surrogate key (V005) and
 * nothing else needs it as an entity — same reasoning as {@code review_item}.
 */
@Service
public class ChangeRequestService {

    private final ChangeRequestRepository changeRequests;
    private final ChangeRequestKeyAllocator keys;
    private final RequirementRepository requirements;
    private final TraceGraphService traceGraph;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ChangeRequestService(ChangeRequestRepository changeRequests, ChangeRequestKeyAllocator keys,
                                 RequirementRepository requirements, TraceGraphService traceGraph,
                                 JdbcTemplate jdbc, AuditService audit) {
        this.changeRequests = changeRequests;
        this.keys = keys;
        this.requirements = requirements;
        this.traceGraph = traceGraph;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ChangeRequest raise(String title, String rationale, List<UUID> requirementIds, UUID actor) {
        if (requirementIds.isEmpty()) {
            throw new IllegalArgumentException("A change request needs at least one requirement");
        }
        ChangeRequest cr = changeRequests.save(new ChangeRequest(keys.next(), title, rationale, actor));
        for (UUID reqId : requirementIds) {
            requirements.findById(reqId).orElseThrow(NoSuchElementException::new); // AC-adjacent: fail loud on a bad id
            jdbc.update(
                "INSERT INTO change_request_requirement (change_request_id, requirement_id) VALUES (?,?)",
                cr.getId(), reqId);
        }
        audit.record(actor, "change_request.raised", "CHANGE_REQUEST", cr.getId(), null,
            Map.of("key", cr.getKey(), "requirements", requirementIds.size()));
        return cr;
    }

    public List<UUID> scope(UUID changeRequestId) {
        return jdbc.queryForList(
            "SELECT requirement_id FROM change_request_requirement WHERE change_request_id = ?",
            UUID.class, changeRequestId);
    }

    public record ImpactSummary(int requirements, int tests, int applications, int briefs) {}

    /**
     * VYB-0391: exact graph queries over the real trace closure, computed fresh every
     * call — never an estimate, and never cached past the moment something in the
     * graph could have changed (AC2).
     */
    @Transactional
    public ImpactSummary computeImpact(UUID changeRequestId) {
        List<UUID> scopeIds = scope(changeRequestId);
        Set<UUID> affectedRequirements = new HashSet<>(scopeIds);
        Set<UUID> affectedTests = new HashSet<>();
        for (UUID reqId : scopeIds) {
            for (var hop : traceGraph.downstream(TraceObjectType.REQUIREMENT, reqId, TraceGraphService.MAX_DEPTH)) {
                if (hop.type() == TraceObjectType.REQUIREMENT) affectedRequirements.add(hop.id());
                else if (hop.type() == TraceObjectType.TEST) affectedTests.add(hop.id());
            }
        }

        int applications = countDistinct("""
            SELECT count(DISTINCT a.id) FROM requirement r
            JOIN capability c ON c.id = r.capability_id
            JOIN application a ON a.id = c.application_id
            WHERE r.id IN (%s)
            """, affectedRequirements);
        int briefs = countDistinct("""
            SELECT count(DISTINCT br.brief_id) FROM brief_requirement br
            WHERE br.requirement_id IN (%s)
            """, affectedRequirements);

        ChangeRequest cr = changeRequests.findById(changeRequestId).orElseThrow(NoSuchElementException::new);
        cr.recordImpact(affectedRequirements.size(), applications);
        changeRequests.save(cr);

        return new ImpactSummary(affectedRequirements.size(), affectedTests.size(), applications, briefs);
    }

    private int countDistinct(String template, Set<UUID> ids) {
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Integer n = jdbc.queryForObject(template.formatted(placeholders), Integer.class, ids.toArray());
        return n == null ? 0 : n;
    }

    @Transactional
    public ChangeRequest decide(UUID id, boolean approve, UUID actor) {
        ChangeRequest cr = changeRequests.findById(id).orElseThrow(NoSuchElementException::new);
        cr.decide(approve, actor);
        changeRequests.save(cr);
        audit.record(actor, approve ? "change_request.approved" : "change_request.rejected",
            "CHANGE_REQUEST", cr.getId(), null, Map.of());
        return cr;
    }

    /**
     * VYB-0390 AC1's gate, checked by {@code RequirementController} before it lets an
     * approved requirement's edit through {@code RequirementService
     * #applyChangeRequestEdit} — this is the one place that knows both "is this
     * change request decided" and "is this requirement actually in its scope".
     */
    public void assertCanEdit(UUID changeRequestId, UUID requirementId) {
        ChangeRequest cr = changeRequests.findById(changeRequestId)
            .orElseThrow(() -> new IllegalArgumentException("No such change request"));
        if (cr.getState() != ChangeRequestState.APPROVED) {
            throw new IllegalStateException("Change request %s is %s, not approved".formatted(cr.getKey(), cr.getState()));
        }
        if (!scope(changeRequestId).contains(requirementId)) {
            throw new IllegalStateException("This requirement is not in the scope of " + cr.getKey());
        }
    }

    /**
     * VYB-0392: mark every downstream link suspect on acceptance. In practice this
     * needs nothing bespoke — {@code applyChangeRequestEdit} bumps the requirement's
     * revision exactly like a normal edit does, and the existing bounded rescan it
     * already triggers (VYB-0161) runs {@code SuspectLinkDetector} on that same id,
     * which is precisely what flags its downstream links suspect (VYB-0159). This
     * method exists only to flip APPROVED &rarr; APPLIED once every scoped
     * requirement has actually been edited through the endpoint above.
     */
    @Transactional
    public ChangeRequest markAppliedIfComplete(UUID changeRequestId, UUID actor) {
        ChangeRequest cr = changeRequests.findById(changeRequestId).orElseThrow(NoSuchElementException::new);
        cr.markApplied();
        changeRequests.save(cr);
        audit.record(actor, "change_request.applied", "CHANGE_REQUEST", cr.getId(), null, Map.of());
        return cr;
    }
}
