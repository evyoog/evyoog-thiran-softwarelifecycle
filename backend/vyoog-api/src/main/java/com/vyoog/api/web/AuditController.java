package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.platform.audit.AuditEvent;
import com.vyoog.platform.audit.AuditQueryService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0722/0755: search, never delete — there is no delete mapping anywhere in this class. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditQueryService query;
    private final PrincipalGuard guard;

    public AuditController(AuditQueryService query, PrincipalGuard guard) {
        this.query = query;
        this.guard = guard;
    }

    public record EventView(
        String id, String occurredAt, String actorId, String actorType, String action,
        String objectType, String objectId, String before, String after, String requestId, String ip) {}

    private static EventView toView(AuditEvent e) {
        return new EventView(e.getId().toString(), e.getOccurredAt().toString(),
            e.getActorId() == null ? null : e.getActorId().toString(), e.getActorType(), e.getAction(),
            e.getObjectType(), e.getObjectId() == null ? null : e.getObjectId().toString(),
            e.getBefore(), e.getAfter(), e.getRequestId(), e.getIp());
    }

    @GetMapping
    public org.springframework.data.domain.Page<EventView> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) UUID objectId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        Page<AuditEvent> result = query.search(
            new AuditQueryService.Filter(actorId, action, objectType, objectId, from, to), page, size);
        return result.map(AuditController::toView);
    }
}
