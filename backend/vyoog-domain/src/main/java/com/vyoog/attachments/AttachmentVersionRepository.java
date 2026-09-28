package com.vyoog.attachments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentVersionRepository extends JpaRepository<AttachmentVersion, UUID> {
    List<AttachmentVersion> findAllByAttachmentIdOrderByVersionAsc(UUID attachmentId);
    Optional<AttachmentVersion> findByAttachmentIdAndVersion(UUID attachmentId, short version);
}
