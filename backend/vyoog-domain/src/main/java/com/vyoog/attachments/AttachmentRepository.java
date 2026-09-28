package com.vyoog.attachments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {
    List<Attachment> findAllByRequirementId(UUID requirementId);
    Optional<Attachment> findByRequirementIdAndFilename(UUID requirementId, String filename);
}
