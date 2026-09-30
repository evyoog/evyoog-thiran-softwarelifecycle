package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.attachments.Attachment;
import com.vyoog.attachments.AttachmentService;
import com.vyoog.attachments.AttachmentVersion;
import com.vyoog.identity.UserProvisioningService;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** VYB-0123/0195: versioned attachments on a requirement. */
@RestController
@RequestMapping("/api/v1/requirements/{requirementId}/attachments")
public class AttachmentController {

    private final AttachmentService service;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public AttachmentController(AttachmentService service, UserProvisioningService provisioning,
                                 PrincipalGuard guard) {
        this.service = service;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    public record AttachmentView(String id, String filename, short currentVersion) {}
    public record VersionView(String id, short version, String contentType, Long sizeBytes, String uploadedAt) {}

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    private static AttachmentView toView(Attachment a) {
        return new AttachmentView(a.getId().toString(), a.getFilename(), a.getCurrentVersion());
    }

    private static VersionView toView(AttachmentVersion v) {
        return new VersionView(v.getId().toString(), v.getVersion(), v.getContentType(), v.getSizeBytes(),
            v.getUploadedAt().toString());
    }

    /**
     * VYB-0901 (F05): reads need a person (not a service account or an email-less token) and
     * the attachment must belong to the requirement in the path — checked in the service.
     * Read access is otherwise as open as reading the requirement itself, which is any signed-in
     * person today; grant-scoped reads are VYB-0908.
     */
    @GetMapping
    public List<AttachmentView> list(@PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return service.list(requirementId).stream().map(AttachmentController::toView).toList();
    }

    @GetMapping("/{attachmentId}/versions")
    public List<VersionView> versions(@PathVariable UUID requirementId, @PathVariable UUID attachmentId,
                                       @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return service.versionsOf(requirementId, attachmentId).stream().map(AttachmentController::toView).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentView upload(@PathVariable UUID requirementId, @RequestParam MultipartFile file,
                                  @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file");
        }
        var result = service.upload(requirementId, file.getOriginalFilename(), file.getContentType(),
            bytes, currentUserId(jwt));
        return toView(result.attachment());
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<ByteArrayResource> downloadCurrent(@PathVariable UUID requirementId,
                                                              @PathVariable UUID attachmentId,
                                                              @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return toResponse(service.downloadCurrent(requirementId, attachmentId));
    }

    @GetMapping("/{attachmentId}/versions/{version}/download")
    public ResponseEntity<ByteArrayResource> downloadVersion(@PathVariable UUID requirementId,
                                                              @PathVariable UUID attachmentId,
                                                              @PathVariable short version,
                                                              @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return toResponse(service.download(requirementId, attachmentId, version));
    }

    private ResponseEntity<ByteArrayResource> toResponse(AttachmentService.Downloaded d) {
        return ResponseEntity.ok()
            .contentType(d.contentType() != null ? MediaType.parseMediaType(d.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(d.filename()).build().toString())
            .body(new ByteArrayResource(d.bytes()));
    }
}
