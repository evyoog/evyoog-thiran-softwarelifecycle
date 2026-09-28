package com.vyoog.savedview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A person's own saved grid filters: private, replaceable by name, never empty. */
@ExtendWith(MockitoExtension.class)
class SavedViewServiceTest {

    @Mock SavedViewRepository views;
    @Mock AuditService audit;

    SavedViewService service;
    UUID me;
    UUID someoneElse;

    @BeforeEach
    void setUp() {
        service = new SavedViewService(views, audit);
        me = UUID.randomUUID();
        someoneElse = UUID.randomUUID();
        lenient().when(views.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void VYB0180_AC1_savingCapturesTheFiltersAndTrimsTheName() {
        when(views.findByOwnerIdAndNameIgnoreCase(me, "My criticals")).thenReturn(Optional.empty());

        SavedView v = service.save(me, "  My criticals  ", "IN_REVIEW", "CRITICAL", null, null, null);

        assertThat(v.getName()).isEqualTo("My criticals");
        assertThat(v.getStatus()).isEqualTo("IN_REVIEW");
        assertThat(v.getPriority()).isEqualTo("CRITICAL");
        assertThat(v.getOwnerId()).isEqualTo(me);
        verify(audit).record(eq(me), eq("saved_view.saved"), eq("SAVED_VIEW"), any(), isNull(), anyMap());
    }

    @Test
    void VYB0180_AC1_savingOverAnExistingNameReplacesItRatherThanFailing() {
        SavedView existing = new SavedView(me, "My criticals", "DRAFT", null, null, null, null);
        when(views.findByOwnerIdAndNameIgnoreCase(me, "My criticals")).thenReturn(Optional.of(existing));

        SavedView v = service.save(me, "My criticals", "APPROVED", "HIGH", null, null, null);

        // "Save as X" when X exists means "make X be this" — a unique-constraint error
        // would be a worse answer than doing what was meant.
        assertThat(v).isSameAs(existing);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getPriority()).isEqualTo("HIGH");
    }

    @Test
    void VYB0180_AC1_aViewWithNoFiltersIsRefused() {
        // It would match every requirement, under a name implying it narrows something.
        assertThatThrownBy(() -> service.save(me, "Everything", null, null, null, "   ", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nothing is filtered");
        verify(views, never()).save(any());
    }

    @Test
    void VYB0180_AC1_aBlankNameIsRefused() {
        assertThatThrownBy(() -> service.save(me, "  ", "DRAFT", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs a name");
    }

    @Test
    void VYB0180_AC1_aTitleFilterAloneIsEnoughToBeWorthSaving() {
        when(views.findByOwnerIdAndNameIgnoreCase(any(), any())).thenReturn(Optional.empty());

        SavedView v = service.save(me, "Lead things", null, null, null, "  lead  ", null);

        assertThat(v.getTitleContains()).isEqualTo("lead");
    }

    @Test
    void VYB0180_AC1_deletingSomebodyElsesViewIsRefused() {
        UUID id = UUID.randomUUID();
        when(views.findById(id)).thenReturn(Optional.of(
            new SavedView(someoneElse, "Theirs", "DRAFT", null, null, null, null)));

        assertThatThrownBy(() -> service.delete(id, me))
            .isInstanceOf(java.util.NoSuchElementException.class);
        verify(views, never()).delete(any());
    }

    @Test
    void VYB0180_AC1_theListIsScopedToTheCaller() {
        when(views.findAllByOwnerIdOrderByNameAsc(me)).thenReturn(List.of());
        service.mine(me);
        verify(views).findAllByOwnerIdOrderByNameAsc(me);
    }
}
