package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.review.Review;
import com.vyoog.review.ReviewComment;
import com.vyoog.review.ReviewParticipantInput;
import com.vyoog.review.ReviewParticipantRole;
import com.vyoog.review.ReviewRepository;
import com.vyoog.review.ReviewService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0300–0307/0360/0361 API surface. */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewRepository reviews;
    private final ReviewService service;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public ReviewController(ReviewRepository reviews, ReviewService service,
                             UserProvisioningService provisioning, PrincipalGuard guard) {
        this.reviews = reviews;
        this.service = service;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record ParticipantInputBody(@NotBlank String userId, @NotBlank String role) {}
    public record OpenRequest(
        @NotBlank String title, String scopeRef,
        @NotEmpty List<String> requirementIds, List<ParticipantInputBody> participants, String closesAt) {}

    public record ParticipantView(String userId, String role, String signedAt, String signatureAcr) {}
    public record ReviewView(
        String id, String title, String scopeRef, String state, String openedAt, String closedAt,
        String closesAt, boolean stale, List<String> requirementIds, List<ParticipantView> participants) {}

    public record CommentBody(@NotBlank String body, String requirementId) {}
    public record CommentView(String id, String requirementId, String authorId, String body, String createdAt) {}

    private ReviewView toView(Review r) {
        return new ReviewView(
            r.getId().toString(), r.getTitle(), r.getScopeRef(), r.getState().name(),
            r.getOpenedAt().toString(), r.getClosedAt() == null ? null : r.getClosedAt().toString(),
            r.getClosesAt() == null ? null : r.getClosesAt().toString(),
            service.isStale(r.getId()),
            service.itemRequirementIds(r.getId()).stream().map(UUID::toString).toList(),
            service.participants(r.getId()).stream()
                .map(p -> new ParticipantView(p.userId().toString(), p.role().name(),
                    p.signedAt() == null ? null : p.signedAt().toString(), p.signatureAcr()))
                .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewView open(@RequestBody OpenRequest body, @AuthenticationPrincipal Jwt jwt) {
        List<UUID> requirementIds = body.requirementIds().stream().map(UUID::fromString).toList();
        List<ReviewParticipantInput> participants = (body.participants() == null ? List.<ParticipantInputBody>of() : body.participants())
            .stream()
            .map(p -> new ReviewParticipantInput(UUID.fromString(p.userId()), ReviewParticipantRole.valueOf(p.role())))
            .toList();
        java.time.Instant closesAt = body.closesAt() == null || body.closesAt().isBlank()
            ? null : java.time.Instant.parse(body.closesAt());
        Review r = service.open(body.title(), body.scopeRef(), requirementIds, participants, currentUserId(jwt), closesAt);
        return toView(r);
    }

    /** VYB-0360: every round, with enough on it (state, stale, my signature) for the frontend to bucket. */
    @GetMapping
    public List<ReviewView> list() {
        return reviews.findAll().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ReviewView get(@PathVariable UUID id) {
        Review r = reviews.findById(id).orElseThrow(NoSuchElementException::new);
        return toView(r);
    }

    @GetMapping("/{id}/comments")
    public List<CommentView> comments(@PathVariable UUID id) {
        return service.comments(id).stream().map(ReviewController::toView).toList();
    }

    private static CommentView toView(ReviewComment c) {
        return new CommentView(
            c.getId().toString(), c.getRequirementId() == null ? null : c.getRequirementId().toString(),
            c.getAuthorId().toString(), c.getBody(), c.getCreatedAt().toString());
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView comment(@PathVariable UUID id, @RequestBody CommentBody body,
                                @AuthenticationPrincipal Jwt jwt) {
        UUID requirementId = body.requirementId() == null ? null : UUID.fromString(body.requirementId());
        return toView(service.comment(id, requirementId, currentUserId(jwt), body.body()));
    }

    /**
     * VYB-0302/0303/0304/0305: refuses a service account and requires step-up before
     * ever reaching {@link ReviewService#sign} — that method trusts both are already
     * true, because those checks need the raw JWT this domain class never sees.
     */
    @PostMapping("/{id}/sign")
    public ReviewView sign(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        guard.requireStepUp(jwt);
        UUID actor = currentUserId(jwt);
        service.sign(id, actor, guard.achievedAcr(jwt), actor);
        return toView(reviews.findById(id).orElseThrow(NoSuchElementException::new));
    }

    @PostMapping("/{id}/close")
    public ReviewView close(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return toView(service.close(id, currentUserId(jwt)));
    }
}
