package com.vyoog.attachments;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * VYB-0123: re-uploading the same filename against a requirement creates a new
 * version rather than overwriting — {@code attachment.current_version} advances,
 * every prior {@link AttachmentVersion} row (and its bytes in the object store)
 * stays exactly as it was.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachments;
    private final AttachmentVersionRepository versions;
    private final S3Client s3;
    private final String bucket;

    public AttachmentService(AttachmentRepository attachments, AttachmentVersionRepository versions, S3Client s3,
                              @Value("${vyoog.storage.bucket:vyoog-attachments}") String bucket) {
        this.attachments = attachments;
        this.versions = versions;
        this.s3 = s3;
        this.bucket = bucket;
        // docs/running-minio-locally.md's own stated contract: "If MinIO is down, the app
        // starts and everything works except uploading, downloading and tenant-export
        // bundling of attachments." Letting a connectivity failure here propagate would
        // take the whole application context down instead — every other feature refused
        // along with it — which is exactly what that doc says shouldn't happen. Failing
        // best-effort at startup and letting upload()/download() throw their own S3
        // exception when actually invoked is what actually delivers that contract.
        try {
            ensureBucketExists();
        } catch (RuntimeException e) {
            log.warn("[attachments] object store unreachable at startup ({}); uploading, downloading and "
                + "tenant-export bundling of attachments will fail until it is — nothing else is affected",
                e.getMessage());
        }
    }

    private void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException notFound) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    public record UploadResult(Attachment attachment, AttachmentVersion version) {}

    @Transactional
    public UploadResult upload(UUID requirementId, String filename, String contentType, byte[] bytes, UUID actor) {
        Attachment attachment = attachments.findByRequirementIdAndFilename(requirementId, filename)
            .map(a -> { a.bumpVersion(); return a; })
            .orElseGet(() -> new Attachment(requirementId, filename));
        attachment = attachments.save(attachment);

        String storageKey = "requirements/%s/%s/v%d-%s"
            .formatted(requirementId, attachment.getId(), attachment.getCurrentVersion(), filename);
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(storageKey).contentType(contentType).build(),
            RequestBody.fromBytes(bytes));

        AttachmentVersion version = versions.save(new AttachmentVersion(
            attachment.getId(), attachment.getCurrentVersion(), storageKey, contentType, bytes.length, actor));
        return new UploadResult(attachment, version);
    }

    public List<Attachment> list(UUID requirementId) {
        return attachments.findAllByRequirementId(requirementId);
    }

    public List<AttachmentVersion> versionsOf(UUID attachmentId) {
        return versions.findAllByAttachmentIdOrderByVersionAsc(attachmentId);
    }

    public record Downloaded(byte[] bytes, String contentType, String filename) {}

    /** VYB-0195 AC1: earlier versions stay downloadable, not just the current one. */
    public Downloaded download(UUID attachmentId, short version) {
        Attachment attachment = attachments.findById(attachmentId).orElseThrow(NoSuchElementException::new);
        AttachmentVersion v = versions.findByAttachmentIdAndVersion(attachmentId, version)
            .orElseThrow(NoSuchElementException::new);
        var object = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(v.getStorageKey()).build());
        try {
            return new Downloaded(object.readAllBytes(), v.getContentType(), attachment.getFilename());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read the stored file", e);
        }
    }

    public Downloaded downloadCurrent(UUID attachmentId) {
        Attachment attachment = attachments.findById(attachmentId).orElseThrow(NoSuchElementException::new);
        return download(attachmentId, attachment.getCurrentVersion());
    }

    /**
     * VYB-0732: raw bytes by storage key alone — {@code TenantExportService} already
     * has the key off every {@code attachment_version} row it dumps, so it doesn't
     * need an attachment id/version pair re-derived just to fetch the same object
     * {@link #download} would.
     */
    public byte[] fetchByStorageKey(String storageKey) {
        var object = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
        try {
            return object.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read the stored file", e);
        }
    }
}
