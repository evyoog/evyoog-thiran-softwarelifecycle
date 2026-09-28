package com.vyoog.savedview;

import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's own saved requirement-grid filters.
 *
 * <p>Private by design: a view is one person's working set, not a shared artefact, so
 * every read and write is scoped to the caller. Sharing a view would need a decision
 * about who may edit whose, which no requirement settles.
 */
@Service
public class SavedViewService {

    private final SavedViewRepository views;
    private final AuditService audit;

    public SavedViewService(SavedViewRepository views, AuditService audit) {
        this.views = views;
        this.audit = audit;
    }

    public List<SavedView> mine(UUID ownerId) {
        return views.findAllByOwnerIdOrderByNameAsc(ownerId);
    }

    /**
     * Saves the caller's current filters under a name, replacing any view of theirs that
     * already has it.
     *
     * <p>Replace rather than refuse: "save as X" when X exists reads as "make X be this",
     * and a unique constraint violation would be a worse answer than doing what was meant.
     *
     * @throws IllegalArgumentException if the name is blank, or if no filter is set —
     *     a view matching everything is the unfiltered grid, saved under a name that
     *     implies it narrows something.
     */
    @Transactional
    public SavedView save(UUID ownerId, String name, String status, String priority, String type,
                          String titleContains, UUID capabilityId) {
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isBlank()) throw new IllegalArgumentException("A saved view needs a name.");

        String cleanTitle = titleContains == null || titleContains.isBlank() ? null : titleContains.strip();
        if (status == null && priority == null && type == null && cleanTitle == null && capabilityId == null) {
            throw new IllegalArgumentException(
                "Nothing is filtered — set at least one filter before saving it as a view.");
        }

        SavedView view = views.findByOwnerIdAndNameIgnoreCase(ownerId, cleanName)
            .map(existing -> {
                existing.replaceFilters(status, priority, type, cleanTitle, capabilityId);
                return existing;
            })
            .orElseGet(() -> new SavedView(ownerId, cleanName, status, priority, type, cleanTitle, capabilityId));

        SavedView saved = views.save(view);
        audit.record(ownerId, "saved_view.saved", "SAVED_VIEW", saved.getId(), null, Map.of(
            "name", cleanName,
            "status", String.valueOf(status),
            "priority", String.valueOf(priority),
            "type", String.valueOf(type),
            "titleContains", String.valueOf(cleanTitle),
            "capabilityId", String.valueOf(capabilityId)));
        return saved;
    }

    /** Scoped to the owner: deleting by id alone would let anyone remove anyone's view. */
    @Transactional
    public void delete(UUID id, UUID ownerId) {
        SavedView view = views.findById(id)
            .filter(v -> v.getOwnerId().equals(ownerId))
            .orElseThrow(() -> new NoSuchElementException("No such saved view"));
        views.delete(view);
        audit.record(ownerId, "saved_view.deleted", "SAVED_VIEW", id,
            Map.of("name", view.getName()), null);
    }
}
