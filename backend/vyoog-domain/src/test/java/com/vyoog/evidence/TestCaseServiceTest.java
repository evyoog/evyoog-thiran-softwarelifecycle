package com.vyoog.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.trace.TraceGraphService;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** VYB-0824: a description is a real, optional field on the human-drafting path — not lost, not required. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestCaseServiceTest {

    @Mock TestCaseRepository testCases;
    @Mock TestCaseKeyAllocator keys;
    @Mock TraceGraphService trace;
    @Mock DetectionSweepService detection;
    @Mock AuditService audit;

    TestCaseService service;
    UUID requirementId;
    UUID actorId;

    @BeforeEach
    void setUp() {
        service = new TestCaseService(testCases, keys, trace, detection, audit);
        requirementId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        when(keys.next()).thenReturn("TC-1");
        when(testCases.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void VYB0824_AC5_draftPersistsDescription() {
        TestCase tc = service.draft("Title", "Steps and expected result.", null, requirementId, actorId);

        assertThat(tc.getDescription()).isEqualTo("Steps and expected result.");
        assertThat(tc.getTitle()).isEqualTo("Title");
        assertThat(tc.getStatus()).isEqualTo(TestCase.Status.DRAFT);
    }

    @Test
    void VYB0824_AC5_nullDescriptionStillWorksBackwardCompatibleWithTheOldTitleOnlyFlow() {
        TestCase tc = service.draft("Title", null, null, requirementId, actorId);

        assertThat(tc.getDescription()).isNull();
        assertThat(tc.getTitle()).isEqualTo("Title");
    }

    @Test
    void VYB0827_AC1_draftPersistsCategory() {
        TestCase tc = service.draft("Title", "d", TestCase.Category.DEPENDENCY, requirementId, actorId);

        assertThat(tc.getCategory()).isEqualTo(TestCase.Category.DEPENDENCY);
    }

    @Test
    void VYB0827_AC1_nullCategoryStillWorksForManualDrafts() {
        TestCase tc = service.draft("Title", "d", null, requirementId, actorId);

        assertThat(tc.getCategory()).isNull();
    }

    @Test
    void VYB0828_AC1_updateChangesTitleDescriptionAndCategory() {
        TestCase existing = new TestCase("TC-9", "Old title", "old desc", TestCase.Category.INDIVIDUAL, TestCase.Status.DRAFT, actorId);
        UUID id = UUID.randomUUID();
        when(testCases.findById(id)).thenReturn(Optional.of(existing));

        TestCase updated = service.update(id, "New title", "new desc", TestCase.Category.DEPENDENCY, actorId);

        assertThat(updated.getTitle()).isEqualTo("New title");
        assertThat(updated.getDescription()).isEqualTo("new desc");
        assertThat(updated.getCategory()).isEqualTo(TestCase.Category.DEPENDENCY);
        // key/status untouched by an edit.
        assertThat(updated.getKey()).isEqualTo("TC-9");
        assertThat(updated.getStatus()).isEqualTo(TestCase.Status.DRAFT);
    }

    @Test
    void VYB0828_AC2_updatingAMissingTestCaseRefusesRatherThanCreatingOne() {
        UUID id = UUID.randomUUID();
        when(testCases.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, "T", "d", null, actorId))
            .isInstanceOf(NoSuchElementException.class);
    }
}
