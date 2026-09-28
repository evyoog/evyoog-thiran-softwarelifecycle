package com.vyoog.platform.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * VYB-0722: search by actor, action, object and time window. AC1 ("tenant-scoped") is
 * satisfied by this whole deployment being one tenant (docs/DECISIONS.md D3) — there
 * is no second tenant's rows this query could leak, so no filter clause exists for it;
 * a real multi-tenant deployment would need one here. AC2: always paged with a capped
 * size — a caller asking for a huge window gets pages, never a query that tries to
 * materialise the whole table at once.
 */
@Service
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository events;

    public AuditQueryService(AuditEventRepository events) {
        this.events = events;
    }

    public record Filter(UUID actorId, String action, String objectType, UUID objectId,
                          Instant from, Instant to) {}

    public Page<AuditEvent> search(Filter filter, int page, int size) {
        int cappedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (filter.actorId() != null) predicates.add(cb.equal(root.get("actorId"), filter.actorId()));
            if (filter.action() != null && !filter.action().isBlank()) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.objectType() != null && !filter.objectType().isBlank()) {
                predicates.add(cb.equal(root.get("objectType"), filter.objectType()));
            }
            if (filter.objectId() != null) predicates.add(cb.equal(root.get("objectId"), filter.objectId()));
            if (filter.from() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
            if (filter.to() != null) predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), filter.to()));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return events.findAll(spec, PageRequest.of(Math.max(0, page), cappedSize, Sort.by(Sort.Direction.DESC, "occurredAt")));
    }
}
