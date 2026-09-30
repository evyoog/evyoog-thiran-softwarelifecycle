package com.vyoog.attachments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * VYB-0901 (F05/F06): an attachment is only reachable through the requirement that owns it, and
 * the sanitised name — never the raw one — reaches the object-store key.
 */
class AttachmentServiceAccessTest {

    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AttachmentVersionRepository versions = mock(AttachmentVersionRepository.class);
    private final S3Client s3 = mock(S3Client.class);
    private final AttachmentService service =
        new AttachmentService(attachments, versions, s3, new AttachmentPolicy(1_000), "bucket");

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Attachment secret = new Attachment(owner, "secret.pdf");
    private final UUID attachmentId = UUID.randomUUID();

    AttachmentServiceAccessTest() {
        org.springframework.test.util.ReflectionTestUtils.setField(secret, "id", attachmentId);
        when(attachments.findById(attachmentId)).thenReturn(Optional.of(secret));
    }

    @Test
    void VYB0901_AC3_downloadingThroughAnotherRequirementsPathIsANotFound() {
        assertThatThrownBy(() -> service.downloadCurrent(other, attachmentId)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.download(other, attachmentId, (short) 1)).isInstanceOf(NoSuchElementException.class);
        verify(s3, never()).getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class));
    }

    @Test
    void VYB0901_AC3_listingVersionsThroughAnotherRequirementsPathIsANotFound() {
        assertThatThrownBy(() -> service.versionsOf(other, attachmentId)).isInstanceOf(NoSuchElementException.class);
        verify(versions, never()).findAllByAttachmentIdOrderByVersionAsc(any());
    }

    @Test
    void VYB0901_AC3_theOwningRequirementCanListVersions() {
        assertThat(service.versionsOf(owner, attachmentId)).isEmpty();
    }

    @Test
    void VYB0901_AC3_anUnknownAttachmentIsTheSameNotFound() {
        assertThatThrownBy(() -> service.versionsOf(owner, UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0901_AC3_theSanitisedNameIsWhatReachesTheObjectKeyAndTheDatabase() {
        when(attachments.findByRequirementIdAndFilename(any(), any())).thenReturn(Optional.empty());
        when(attachments.save(any())).thenAnswer(i -> i.getArgument(0));
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());

        var result = service.upload(owner, "../../other/tenant/x.txt", "text/plain", new byte[] {1, 2, 3}, UUID.randomUUID());

        assertThat(result.attachment().getFilename()).isEqualTo("x.txt");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).startsWith("requirements/" + owner + "/").endsWith("-x.txt").doesNotContain("..");
    }

    @Test
    void VYB0901_AC3_aRefusedUploadWritesNothing() {
        assertThatThrownBy(() -> service.upload(owner, "page.html", "text/html", new byte[] {1}, UUID.randomUUID()))
            .isInstanceOf(AttachmentRejectedException.class);
        assertThatThrownBy(() -> service.upload(owner, "big.pdf", "application/pdf", new byte[1_001], UUID.randomUUID()))
            .isInstanceOf(AttachmentRejectedException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(attachments, never()).save(any());
    }
}
