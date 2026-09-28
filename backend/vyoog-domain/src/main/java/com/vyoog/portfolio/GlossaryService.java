package com.vyoog.portfolio;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryService {

    private final GlossaryTermRepository terms;
    private final GlossaryTermUsageRepository usages;

    public GlossaryService(GlossaryTermRepository terms, GlossaryTermUsageRepository usages) {
        this.terms = terms;
        this.usages = usages;
    }

    @Transactional
    public GlossaryTerm createTerm(String term, String definition, UUID ownerId) {
        GlossaryTerm t = new GlossaryTerm(term, definition);
        t.setOwnerId(ownerId);
        return terms.save(t);
    }

    /** VYB-0102 AC2: usage across applications is queryable — this both records and enables that query. */
    @Transactional
    public GlossaryTermUsage recordUsage(UUID termId, UUID applicationId, String definitionOverride) {
        if (!terms.existsById(termId)) {
            throw new NoSuchElementException("No such glossary term: " + termId);
        }
        GlossaryTermUsage usage = usages.findByTermIdAndApplicationId(termId, applicationId)
            .orElseGet(() -> new GlossaryTermUsage(termId, applicationId, definitionOverride));
        usage.setDefinition(definitionOverride);
        return usages.save(usage);
    }

    public record DefinitionVariant(String definition, List<UUID> applicationIds) {}
    public record TermConflict(UUID termId, String term, List<DefinitionVariant> variants) {}

    /**
     * VYB-0103: a term defined differently by two applications. An application with
     * no override uses the canonical definition, so it's grouped under that variant
     * too — a conflict is exactly when a term has more than one distinct effective
     * definition among the applications that actually use it.
     */
    public List<TermConflict> findConflicts() {
        List<TermConflict> conflicts = new java.util.ArrayList<>();
        for (GlossaryTerm term : terms.findAll()) {
            List<GlossaryTermUsage> termUsages = usages.findAllByTermId(term.getId());
            if (termUsages.size() < 2) continue; // need at least two applications to conflict

            Map<String, List<UUID>> byEffectiveDefinition = termUsages.stream()
                .collect(Collectors.groupingBy(
                    u -> u.getDefinition() != null ? u.getDefinition() : term.getDefinition(),
                    Collectors.mapping(GlossaryTermUsage::getApplicationId, Collectors.toList())));

            if (byEffectiveDefinition.size() > 1) {
                List<DefinitionVariant> variants = byEffectiveDefinition.entrySet().stream()
                    .map(e -> new DefinitionVariant(e.getKey(), e.getValue()))
                    .toList();
                conflicts.add(new TermConflict(term.getId(), term.getTerm(), variants));
            }
        }
        return conflicts;
    }
}
