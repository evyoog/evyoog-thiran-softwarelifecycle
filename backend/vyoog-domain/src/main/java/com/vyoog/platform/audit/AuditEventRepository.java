package com.vyoog.platform.audit;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * VYB-0722: {@link JpaSpecificationExecutor} gives {@link AuditQueryService} a
 * dynamic, always-paged filter over actor/action/object/window without hand-rolling
 * either the SQL or the entity hydration — every column ({@code id}, {@code
 * occurred_at}, {@code ip} included) comes back the normal Hibernate way.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {
    Optional<AuditEvent> findByObjectIdAndAction(UUID objectId, String action);
}
