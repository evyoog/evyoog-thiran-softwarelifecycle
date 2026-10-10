package com.vyoog.attachments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vyoog.platform.tx.NetworkCallGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * VYB-0940 (F31): the file goes to object storage before, and outside, the transaction that writes its rows; if the rows cannot be
 * written the file is deleted again.
 */
class AttachmentUploadTransactionTest {

    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AttachmentVersionRepository versions = mock(AttachmentVersionRepository.class);
    private final S3Client s3 = mock(S3Client.class);
    private final List<String> events = new ArrayList<>();
    private final UUID requirement = UUID.randomUUID();

    private AttachmentService service(TransactionOperations tx) {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(i -> {
            events.add("put, in transaction: " + NetworkCallGuard.inTransaction());
            return PutObjectResponse.builder().build();
        });
        when(attachments.findByRequirementIdAndFilename(any(), any())).thenReturn(Optional.empty());
        when(attachments.save(any())).thenAnswer(i -> i.getArgument(0));
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));
        return new AttachmentService(attachments, versions, s3, new AttachmentPolicy(1_000), tx, NetworkCallGuard.refusing(), "bucket");
    }

    /** A transaction that is open while its body runs, as a real one is. */
    private TransactionOperations openTransaction() {
        return new TransactionOperations() {
            @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                events.add("transaction opened");
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try {
                    return action.doInTransaction(null);
                } finally {
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                    events.add("transaction closed");
                }
            }
        };
    }

    @AfterEach
    void closeTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void VYB0940_AC26_theFileIsStoredBeforeTheTransactionOpensAndOutsideIt() {
        service(openTransaction()).upload(requirement, "spec.txt", "text/plain", new byte[] {1}, UUID.randomUUID());

        assertThat(events).containsExactly("put, in transaction: false", "transaction opened", "transaction closed");
    }

    @Test
    void VYB0940_AC27_ifTheRowsCannotBeWrittenTheFileIsDeletedAgainAndTheFailureStillReachesTheCaller() {
        AttachmentService service = service(openTransaction());
        when(versions.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(requirement, "spec.txt", "text/plain", new byte[] {1}, UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class).hasMessage("database down");

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        InOrder order = inOrder(s3);
        order.verify(s3).putObject(put.capture(), any(RequestBody.class));
        order.verify(s3).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(put.getValue().key());
    }

    @Test
    void VYB0940_AC28_aFailureToDeleteTheFileDoesNotHideTheRealFailure() {
        AttachmentService service = service(openTransaction());
        when(versions.save(any())).thenThrow(new IllegalStateException("database down"));
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new IllegalStateException("store down"));

        assertThatThrownBy(() -> service.upload(requirement, "spec.txt", "text/plain", new byte[] {1}, UUID.randomUUID()))
            .hasMessage("database down");
    }

    @Test
    void VYB0940_AC29_ifTheStoreRefusesTheFileNoRowIsWrittenAndNothingIsDeleted() {
        AttachmentService service = service(openTransaction());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(new IllegalStateException("store down"));

        assertThatThrownBy(() -> service.upload(requirement, "spec.txt", "text/plain", new byte[] {1}, UUID.randomUUID()))
            .hasMessage("store down");

        verify(attachments, never()).save(any());
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void VYB0940_AC30_anUploadCalledInsideSomeoneElsesTransactionIsRefusedBeforeAnythingIsSent() {
        AttachmentService service = service(TransactionOperations.withoutTransaction());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> service.upload(requirement, "spec.txt", "text/plain", new byte[] {1}, UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("object storage upload");

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void VYB0940_AC31_eachUploadGetsItsOwnKeyEvenForTheSameFilename() {
        AttachmentService service = service(TransactionOperations.withoutTransaction());

        service.upload(requirement, "spec.txt", "text/plain", new byte[] {1}, UUID.randomUUID());
        service.upload(requirement, "spec.txt", "text/plain", new byte[] {2}, UUID.randomUUID());

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getAllValues().get(0).key()).isNotEqualTo(put.getAllValues().get(1).key());
        assertThat(put.getAllValues()).allSatisfy(p -> assertThat(p.key()).startsWith("requirements/" + requirement + "/").endsWith("-spec.txt"));
    }

    @Test
    void VYB0940_AC45_storingTheFileAndWritingItsRowsCanBeSeparatedSoACallerCanPutTheRowsInItsOwnTransaction() {
        AttachmentService service = service(TransactionOperations.withoutTransaction());

        AttachmentService.StoredFile file = service.storeFile(requirement, "evidence.png", "image/png", new byte[] {1, 2, 3});

        verify(attachments, never()).save(any());
        assertThat(file.filename()).isEqualTo("evidence.png");
        assertThat(file.size()).isEqualTo(3);
        var result = service.attach(file, UUID.randomUUID());
        assertThat(result.version().getStorageKey()).isEqualTo(file.storageKey());
        service.discard(file);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(file.storageKey());
    }
}
