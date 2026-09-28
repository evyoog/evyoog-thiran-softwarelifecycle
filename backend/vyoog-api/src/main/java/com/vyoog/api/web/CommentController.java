package com.vyoog.api.web;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.requirements.CommentService;
import com.vyoog.requirements.RequirementComment;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0124: comments on a requirement. */
@RestController
@RequestMapping("/api/v1/requirements/{requirementId}/comments")
public class CommentController {

    private final CommentService service;
    private final UserProvisioningService provisioning;

    public CommentController(CommentService service, UserProvisioningService provisioning) {
        this.service = service;
        this.provisioning = provisioning;
    }

    public record CommentView(String id, String authorId, String body, String createdAt, List<String> mentionedUserIds) {}
    public record AddComment(@NotBlank String body) {}

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    @GetMapping
    public List<CommentView> list(@PathVariable UUID requirementId) {
        return service.list(requirementId).stream()
            .map(c -> new CommentView(c.getId().toString(), c.getAuthorId().toString(), c.getBody(),
                c.getCreatedAt().toString(), List.of()))
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView add(@PathVariable UUID requirementId, @RequestBody AddComment body,
                            @AuthenticationPrincipal Jwt jwt) {
        var result = service.add(requirementId, currentUserId(jwt), body.body());
        RequirementComment c = result.comment();
        return new CommentView(c.getId().toString(), c.getAuthorId().toString(), c.getBody(),
            c.getCreatedAt().toString(), result.mentionedUserIds().stream().map(UUID::toString).toList());
    }
}
