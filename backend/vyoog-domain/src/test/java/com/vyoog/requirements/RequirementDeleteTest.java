package com.vyoog.requirements;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.ai.EmbeddingService;
import com.vyoog.brief.BriefStalenessService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** VYB-0666: deleting a requirement removes it from the register without erasing that it existed. */
@ExtendWith(MockitoExtension.class)
class RequirementDeleteTest {

    @Mock RequirementRepository requirements;
    @Mock RequirementRevisionRepository revisions;
    @Mock AcceptanceCriterionRepository criteria;
    @Mock RequirementKeyAllocator keys;
    @Mock AuditService audit;
    @Mock DetectionSweepService detection;
    @Mock BriefStalenessService briefStaleness;
    @Mock EmbeddingService embeddings;
    @Mock JdbcTemplate jdbc;
    @Mock RequirementEnrichmentService enrichment;
    @Mock com.vyoog.identity.GrantResolver grantResolver;

    RequirementService service;
    UUID actor;
    UUID id;
    Requirement requirement;

    @BeforeEach
    void setUp() {
        service = new RequirementService(
            requirements, revisions, criteria, keys, audit, detection, briefStaleness, embeddings,
            new QualityScoreService(), jdbc, enrichment, new RequirementTransitionAuthorizer(grantResolver));
        actor = UUID.randomUUID();
        id = UUID.randomUUID();
        requirement = new Requirement("VY-1042", "Capture punch events", "The system shall capture punches.", actor);
    }

    @Test
    void VYB0666_AC1_marksTheRequirementDeletedAndSavesIt() {
        when(requirements.findById(id)).thenReturn(Optional.of(requirement));

        service.delete(id, "duplicate of VY-1039", actor);

        assertThat(requirement.isDeleted()).isTrue();
        verify(requirements).save(requirement);
    }

    @Test
    void VYB0666_AC1_recordsWhatWasDeletedAndWhyOnTheAuditTrail() {
        // The row is gone from every view, so the audit entry is the only place left that
        // can answer "why did VY-1042 vanish". It has to carry the key, not just the id.
        when(requirements.findById(id)).thenReturn(Optional.of(requirement));

        service.delete(id, "duplicate of VY-1039", actor);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(actor), eq("requirement.deleted"), eq("REQUIREMENT"), eq(id), any(),
            details.capture());
        assertThat(details.getValue())
            .containsEntry("key", "VY-1042")
            .containsEntry("reason", "duplicate of VY-1039")
            .containsEntry("status", "DRAFT");
    }

    @Test
    void VYB0666_AC1_deletingTwiceIsNotAnErrorAndDoesNotAuditTwice() {
        // Two clicks on a slow connection, or a retried request. The caller asked for the
        // state the row is already in, which is not a failure — but a second audit entry
        // would suggest somebody deleted it twice.
        requirement.markDeleted(actor);
        when(requirements.findById(id)).thenReturn(Optional.of(requirement));

        service.delete(id, null, actor);

        verify(requirements, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void VYB0666_AC1_aMissingRequirementIsNotFoundRatherThanSilentlyIgnored() {
        when(requirements.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, null, actor))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0666_AC1_keepsTheRowSoItsHistoryStillResolves() {
        // Soft delete is the point: revisions, trace links and any brief that quoted this
        // requirement still reference the row. Nothing here may delete it for real.
        when(requirements.findById(id)).thenReturn(Optional.of(requirement));

        service.delete(id, null, actor);

        // RequirementRepository extends JpaSpecificationExecutor too, so a bare any()
        // cannot tell delete(T) from delete(Specification<T>) — the entity overload is the
        // one that would erase the row.
        verify(requirements, never()).delete(any(Requirement.class));
        verify(requirements, never()).deleteById(any());
        assertThat(requirement.getKey()).isEqualTo("VY-1042");
    }
}
