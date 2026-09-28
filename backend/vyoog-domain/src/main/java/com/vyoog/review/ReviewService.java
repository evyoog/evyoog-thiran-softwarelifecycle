package com.vyoog.review;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.time.Instant;
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
 * {@code review_item} and {@code review_participant} have no surrogate key of their
 * own (composite primary keys — see V001__baseline.sql) and nothing else in the
 * codebase needs to load them as JPA entities, so — same choice already made for
 * {@code trace_closure} in {@link com.vyoog.trace.TraceGraphService} — they're read
 * and written here as plain SQL, not mapped classes.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final ReviewCommentRepository comments;
    private final RequirementRepository requirements;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final GrantService grants;

    public ReviewService(ReviewRepository reviews, ReviewCommentRepository comments,
                          RequirementRepository requirements, JdbcTemplate jdbc, AuditService audit,
                          GrantService grants) {
        this.reviews = reviews;
        this.comments = comments;
        this.requirements = requirements;
        this.jdbc = jdbc;
        this.audit = audit;
        this.grants = grants;
    }

    /** VYB-0300 AC1: each item's revision is frozen at the moment the round opens. */
    @Transactional
    public Review open(String title, String scopeRef, List<UUID> requirementIds,
                        List<ReviewParticipantInput> participants, UUID actor) {
        return open(title, scopeRef, requirementIds, participants, actor, null);
    }

    /** VYB-0372 (session 14): an optional window-closes-by date, purely informational — nothing enforces it. */
    @Transactional
    public Review open(String title, String scopeRef, List<UUID> requirementIds,
                        List<ReviewParticipantInput> participants, UUID actor, java.time.Instant closesAt) {
        if (requirementIds.isEmpty()) {
            throw new IllegalArgumentException("A review round needs at least one requirement");
        }
        Review review = new Review(title, scopeRef);
        review.setClosesAt(closesAt);
        // saveAndFlush — same JPA-defers/JdbcTemplate-doesn't hazard as BriefService#generate.
        // Both review_item and review_participant below are JdbcTemplate writes against this
        // row's id, and the method already refuses an empty requirement list above, so every
        // call reaches them.
        review = reviews.saveAndFlush(review);
        Set<UUID> capabilityIds = new HashSet<>();
        for (UUID reqId : requirementIds) {
            Requirement r = requirements.findById(reqId).orElseThrow(NoSuchElementException::new);
            jdbc.update("INSERT INTO review_item (review_id, requirement_id, revision) VALUES (?,?,?)",
                review.getId(), reqId, r.getRevision());
            if (r.getCapabilityId() != null) capabilityIds.add(r.getCapabilityId());
        }

        Set<UUID> explicitUserIds = new HashSet<>();
        for (ReviewParticipantInput p : participants) {
            jdbc.update("INSERT INTO review_participant (review_id, user_id, role) VALUES (?,?,?)",
                review.getId(), p.userId(), p.role().name());
            explicitUserIds.add(p.userId());
        }

        // VYB-0343: every approver whose grant covers one of this round's requirements
        // is a participant from the moment the round opens, whether or not the caller
        // named them explicitly — that's what "holding a grant" means as a *task*
        // trigger, and there'd be nothing for them to sign otherwise.
        Set<UUID> autoApprovers = new HashSet<>();
        for (UUID capabilityId : capabilityIds) {
            autoApprovers.addAll(grants.usersHolding(AccessRole.APPROVER, capabilityId));
        }
        autoApprovers.removeAll(explicitUserIds);
        for (UUID userId : autoApprovers) {
            jdbc.update("INSERT INTO review_participant (review_id, user_id, role) VALUES (?,?,'APPROVER')",
                review.getId(), userId);
        }

        audit.record(actor, "review.opened", "REVIEW", review.getId(), null,
            Map.of("title", title, "items", requirementIds.size(), "autoApprovers", autoApprovers.size()));
        return review;
    }

    /** VYB-0300 AC2: a round is stale once any item's requirement has moved on. */
    public boolean isStale(UUID reviewId) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM review_item ri
            JOIN requirement r ON r.id = ri.requirement_id
            WHERE ri.review_id = ? AND r.revision <> ri.revision
            """, Integer.class, reviewId);
        return count != null && count > 0;
    }

    public List<UUID> itemRequirementIds(UUID reviewId) {
        return jdbc.queryForList(
            "SELECT requirement_id FROM review_item WHERE review_id = ?", UUID.class, reviewId);
    }

    public record ParticipantView(
        UUID userId, ReviewParticipantRole role, Instant signedAt, String signatureAcr) {}

    public List<ParticipantView> participants(UUID reviewId) {
        return jdbc.query("""
            SELECT user_id, role, signed_at, signature_acr FROM review_participant WHERE review_id = ?
            """,
            (rs, n) -> new ParticipantView(
                UUID.fromString(rs.getString("user_id")),
                ReviewParticipantRole.valueOf(rs.getString("role")),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
                rs.getString("signature_acr")),
            reviewId);
    }

    public List<ReviewComment> comments(UUID reviewId) {
        return comments.findAllByReviewIdOrderByCreatedAtAsc(reviewId);
    }

    /** VYB-0306: against the round as a whole, or one requirement in it. */
    public ReviewComment comment(UUID reviewId, UUID requirementId, UUID authorId, String body) {
        return comments.save(new ReviewComment(reviewId, requirementId, authorId, body));
    }

    /**
     * VYB-0302/0304: records a signature already cleared for step-up (the controller
     * checks that — see {@code ReviewController} — because it needs the raw JWT claim
     * this domain-layer class deliberately never sees) and for separation of duties
     * (checked here, since it needs the requirement data this class already has).
     *
     * <p>Idempotent on a repeat call from the same signer (VYB-0302 AC2 read the other
     * way: append-only also means a second attempt to sign is a no-op, not an error).
     */
    @Transactional
    public void sign(UUID reviewId, UUID userId, String achievedAcr, UUID actor) {
        Review review = reviews.findById(reviewId).orElseThrow(NoSuchElementException::new);
        if (review.getState() != ReviewState.OPEN) {
            throw new IllegalStateException("This round is %s, not open".formatted(review.getState()));
        }

        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                "SELECT role, signed_at FROM review_participant WHERE review_id = ? AND user_id = ?",
                reviewId, userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Not a participant on this round");
        }
        ReviewParticipantRole role = ReviewParticipantRole.valueOf((String) row.get("role"));
        if (role == ReviewParticipantRole.OBSERVER) {
            throw new IllegalStateException("Observers cannot sign"); // VYB-0301 AC2
        }
        if (row.get("signed_at") != null) {
            return; // already signed — repeat clicks are a no-op, not an error
        }

        if (role == ReviewParticipantRole.APPROVER) {
            enforceSeparationOfDuties(reviewId, userId, actor);
        }

        jdbc.update(
            "UPDATE review_participant SET signed_at = now(), signature_acr = ? WHERE review_id = ? AND user_id = ?",
            achievedAcr, reviewId, userId);
        audit.record(actor, "review.signed", "REVIEW", reviewId, null,
            Map.of("userId", userId.toString(), "role", role.name()));
    }

    /** VYB-0304: an approver cannot be the requirement's owner or author. */
    private void enforceSeparationOfDuties(UUID reviewId, UUID userId, UUID actor) {
        for (UUID reqId : itemRequirementIds(reviewId)) {
            Requirement r = requirements.findById(reqId).orElseThrow(NoSuchElementException::new);
            boolean isOwner = userId.equals(r.getOwnerId());
            boolean isAuthor = userId.equals(r.getCreatedBy());
            if (isOwner || isAuthor) {
                audit.record(actor, "review.approval_refused_separation_of_duties", "REVIEW", reviewId,
                    null, Map.of("requirementId", reqId.toString(), "reason", isOwner ? "owner" : "author"));
                throw new IllegalStateException(
                    "You cannot approve %s — you are its %s".formatted(r.getKey(), isOwner ? "owner" : "author"));
            }
        }
    }

    private record BlockingClarification(UUID requirementId, String question) {}

    /** VYB-0301 AC1 and VYB-0307: no approver, or an open blocking clarification. */
    @Transactional
    public Review close(UUID reviewId, UUID actor) {
        Review review = reviews.findById(reviewId).orElseThrow(NoSuchElementException::new);

        boolean hasApprover = participants(reviewId).stream()
            .anyMatch(p -> p.role() == ReviewParticipantRole.APPROVER);
        if (!hasApprover) {
            throw new IllegalStateException("A round with no approver cannot close");
        }

        Set<UUID> itemIds = new HashSet<>(itemRequirementIds(reviewId));
        List<BlockingClarification> openBlocking = jdbc.query("""
            SELECT requirement_id, question FROM clarification
            WHERE state = 'OPEN' AND blocks_task = true
            """,
            (rs, n) -> new BlockingClarification(
                UUID.fromString(rs.getString("requirement_id")), rs.getString("question")));
        openBlocking.stream().filter(b -> itemIds.contains(b.requirementId())).findFirst()
            .ifPresent(b -> {
                throw new IllegalStateException(
                    "Cannot close — blocking clarification open: \"%s\"".formatted(b.question()));
            });

        review.close();
        reviews.save(review);
        audit.record(actor, "review.closed", "REVIEW", reviewId, null, Map.of());
        return review;
    }
}
