package com.vyoog.attachments;

import com.vyoog.platform.tx.NetworkCallGuard;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
    private final AttachmentPolicy policy;
    private final TransactionOperations tx;
    private final NetworkCallGuard guard;

    public AttachmentService(AttachmentRepository attachments, AttachmentVersionRepository versions, S3Client s3,
                              AttachmentPolicy policy, TransactionOperations tx, NetworkCallGuard guard,
                              @Value("${vyoog.storage.bucket:vyoog-attachments}") String bucket) {
        this.tx = tx;
        this.guard = guard;
        this.attachments = attachments;
        this.versions = versions;
        this.s3 = s3;
        this.policy = policy;
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

    /** A file already in object storage whose rows are not written yet (VYB-0940). */
    public record StoredFile(UUID requirementId, String filename, String storageKey, String contentType, int size) {}

    /**
     * VYB-0940: the file goes to object storage first, outside any database transaction, under a key made from a fresh id; the
     * rows are then written in one short transaction. The transaction used to wrap the upload, holding a connection for as long as
     * the store took, and a rollback left the file behind. Now, if the rows cannot be written the file is deleted again (best
     * effort; a failure to delete is logged, and the file is then an unreferenced object that nothing reads).
     */
    public UploadResult upload(UUID requirementId, String rawFilename, String contentType, byte[] bytes, UUID actor) {
        StoredFile file = storeFile(requirementId, rawFilename, contentType, bytes);
        try {
            return attach(file, actor);
        } catch (RuntimeException e) {
            discard(file);
            throw e;
        }
    }

    /**
     * The first half of an upload: checks the file, then puts it to object storage. Writes no row. Must not be called inside a
     * database transaction (the guard refuses it in tests). A caller that needs the rows to land in a transaction of its own,
     * with other rows, calls this first, then {@link #attach} inside that transaction, and {@link #discard} if it fails.
     */
    public StoredFile storeFile(UUID requirementId, String rawFilename, String contentType, byte[] bytes) {
        // VYB-0901: size, type and name are checked here, and it is the sanitised name that
        // reaches both the database and the object-store key.
        String filename = policy.check(rawFilename, contentType, bytes.length);
        String storageKey = "requirements/%s/%s-%s".formatted(requirementId, UUID.randomUUID(), filename);

        guard.beforeNetworkCall("object storage upload");
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(storageKey).contentType(contentType).build(),
            RequestBody.fromBytes(bytes));
        return new StoredFile(requirementId, filename, storageKey, contentType, bytes.length);
    }

    /** The second half: the attachment and version rows for a file already stored. Joins the caller's transaction if there is one. */
    public UploadResult attach(StoredFile file, UUID actor) {
        return tx.execute(status -> {
            Attachment attachment = attachments.findByRequirementIdAndFilename(file.requirementId(), file.filename())
                .map(a -> { a.bumpVersion(); return a; })
                .orElseGet(() -> new Attachment(file.requirementId(), file.filename()));
            attachment = attachments.save(attachment);
            AttachmentVersion version = versions.save(new AttachmentVersion(
                attachment.getId(), attachment.getCurrentVersion(), file.storageKey(), file.contentType(), file.size(), actor));
            return new UploadResult(attachment, version);
        });
    }

    /** Deletes a stored file whose rows were not written; best effort. */
    public void discard(StoredFile file) {
        deleteQuietly(file.storageKey());
    }

    private void deleteQuietly(String storageKey) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (RuntimeException e) {
            log.warn("[attachments] the rows for {} could not be written and the file could not be deleted again ({}); "
                + "it is an unreferenced object in the store", storageKey, e.getMessage());
        }
    }

    public List<Attachment> list(UUID requirementId) {
        return attachments.findAllByRequirementId(requirementId);
    }

    /**
     * VYB-0901 (F05): the attachment must belong to the requirement in the path. Without
     * this a caller could put any requirement id in the URL and read any attachment id —
     * a 404 for a mismatch, indistinguishable from an attachment that does not exist.
     */
    private Attachment requireOwned(UUID requirementId, UUID attachmentId) {
        return attachments.findById(attachmentId)
            .filter(a -> a.getRequirementId().equals(requirementId))
            .orElseThrow(NoSuchElementException::new);
    }

    public List<AttachmentVersion> versionsOf(UUID requirementId, UUID attachmentId) {
        requireOwned(requirementId, attachmentId);
        return versions.findAllByAttachmentIdOrderByVersionAsc(attachmentId);
    }

    public record Downloaded(byte[] bytes, String contentType, String filename) {}

    /** VYB-0195 AC1: earlier versions stay downloadable, not just the current one. */
    public Downloaded download(UUID requirementId, UUID attachmentId, short version) {
        Attachment attachment = requireOwned(requirementId, attachmentId);
        AttachmentVersion v = versions.findByAttachmentIdAndVersion(attachmentId, version)
            .orElseThrow(NoSuchElementException::new);
        var object = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(v.getStorageKey()).build());
        try {
            return new Downloaded(object.readAllBytes(), v.getContentType(), attachment.getFilename());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read the stored file", e);
        }
    }

    public Downloaded downloadCurrent(UUID requirementId, UUID attachmentId) {
        Attachment attachment = requireOwned(requirementId, attachmentId);
        return download(requirementId, attachmentId, attachment.getCurrentVersion());
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
