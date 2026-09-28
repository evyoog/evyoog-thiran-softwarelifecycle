package com.vyoog.requirements;

import com.vyoog.detection.DetectionSweepService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordered children of a requirement (VYB-0114). Deliberately separate from
 * {@link RequirementService}: adding, editing, reordering or removing a criterion
 * never bumps the requirement's revision — only the statement/title/type/priority/
 * capability content does.
 */
@Service
public class AcceptanceCriterionService {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceCriterionService.class);

    private final AcceptanceCriterionRepository criteria;
    private final RequirementRepository requirements;
    private final DetectionSweepService detection;

    public AcceptanceCriterionService(AcceptanceCriterionRepository criteria,
                                       RequirementRepository requirements,
                                       DetectionSweepService detection) {
        this.criteria = criteria;
        this.requirements = requirements;
        this.detection = detection;
    }

    /** VYB-0161: adding/removing a criterion is exactly what the "noac" rule reads. */
    private void rescan(UUID requirementId) {
        try {
            criteria.flush();
            detection.rescanObject(requirementId);
        } catch (Exception e) {
            log.warn("[detection] bounded rescan failed for {}: {}", requirementId, e.getMessage());
        }
    }

    /**
     * VYB-0666: how many criteria each of these requirements has, for the whole page at
     * once. A requirement with none is simply absent from the map — the caller defaults it
     * to zero rather than this returning a row of zeroes for every id.
     */
    public java.util.Map<UUID, Integer> countsFor(List<UUID> requirementIds) {
        if (requirementIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, Integer> counts = new java.util.HashMap<>();
        for (Object[] row : criteria.countsByRequirementIds(requirementIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    public List<AcceptanceCriterion> list(UUID requirementId) {
        return criteria.findAllByRequirementIdOrderByOrdinalAsc(requirementId);
    }

    @Transactional
    public AcceptanceCriterion add(UUID requirementId, String text) {
        if (!requirements.existsById(requirementId)) {
            throw new NoSuchElementException("No such requirement: " + requirementId);
        }
        short nextOrdinal = (short) (criteria.countByRequirementId(requirementId) + 1);
        AcceptanceCriterion saved = criteria.save(new AcceptanceCriterion(requirementId, nextOrdinal, text));
        rescan(requirementId);
        return saved;
    }

    /** VYB-0191: editing existing text in place, alongside add/reorder/remove — never touches ordinal. */
    @Transactional
    public AcceptanceCriterion edit(UUID id, String text) {
        AcceptanceCriterion c = criteria.findById(id).orElseThrow(NoSuchElementException::new);
        c.setText(text);
        AcceptanceCriterion saved = criteria.save(c);
        rescan(c.getRequirementId());
        return saved;
    }

    @Transactional
    public void remove(UUID id) {
        AcceptanceCriterion c = criteria.findById(id).orElseThrow(NoSuchElementException::new);
        UUID requirementId = c.getRequirementId();
        criteria.delete(c);
        renumber(requirementId);
        rescan(requirementId);
    }

    /** VYB-0114 AC1: order is stable across reads. Reordering is a full replace of order. */
    @Transactional
    public List<AcceptanceCriterion> reorder(UUID requirementId, List<UUID> orderedIds) {
        List<AcceptanceCriterion> current = criteria.findAllByRequirementIdOrderByOrdinalAsc(requirementId);
        if (current.size() != orderedIds.size()) {
            throw new IllegalArgumentException("Reorder must include every existing criterion exactly once");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            UUID id = orderedIds.get(i);
            AcceptanceCriterion c = current.stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a criterion of this requirement: " + id));
            c.setOrdinal((short) (i + 1));
        }
        return criteria.saveAll(current);
    }

    private void renumber(UUID requirementId) {
        List<AcceptanceCriterion> remaining = criteria.findAllByRequirementIdOrderByOrdinalAsc(requirementId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setOrdinal((short) (i + 1));
        }
        criteria.saveAll(remaining);
    }
}
