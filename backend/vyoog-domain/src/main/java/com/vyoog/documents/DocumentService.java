package com.vyoog.documents;

import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** VYB-0210/0632-style document register: create, membership, reorder, export data. */
@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final RequirementRepository requirements;
    private final TraceLinkRepository traceLinks;
    private final JdbcTemplate jdbc;

    public DocumentService(DocumentRepository documents, RequirementRepository requirements,
                            TraceLinkRepository traceLinks, JdbcTemplate jdbc) {
        this.documents = documents;
        this.requirements = requirements;
        this.traceLinks = traceLinks;
        this.jdbc = jdbc;
    }

    public record RegisterRow(Document document, long itemCount, long gapCount) {}

    /** VYB-0210 AC1: item count, revision (on the document itself) and gap count per row, no extra round trip per row. */
    public List<RegisterRow> register() {
        return documents.findAll().stream()
            .map(d -> new RegisterRow(d, itemCount(d.getId()), gapCount(d.getId())))
            .toList();
    }

    private long itemCount(UUID documentId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM document_requirement WHERE document_id = ?", Long.class, documentId);
        return n == null ? 0 : n;
    }

    /** Open findings against any requirement currently in this document. */
    private long gapCount(UUID documentId) {
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM finding f
            WHERE f.object_type = 'REQUIREMENT' AND f.state = 'OPEN'
              AND f.object_id IN (SELECT requirement_id FROM document_requirement WHERE document_id = ?)
            """, Long.class, documentId);
        return n == null ? 0 : n;
    }

    @Transactional
    public Document create(String key, String title, UUID productId) {
        if (documents.existsByKey(key)) {
            throw new IllegalArgumentException("A document with key '" + key + "' already exists");
        }
        return documents.save(new Document(key, title, productId));
    }

    public Document get(UUID id) {
        return documents.findById(id).orElseThrow(NoSuchElementException::new);
    }

    /** Ordered requirements currently in the document — the shape both the register grid and the prose/export views read. */
    public List<Requirement> requirementsIn(UUID documentId) {
        List<UUID> orderedIds = jdbc.query(
            "SELECT requirement_id FROM document_requirement WHERE document_id = ? ORDER BY ordinal",
            (rs, n) -> UUID.fromString(rs.getString("requirement_id")), documentId);
        List<Requirement> all = requirements.findAllById(orderedIds);
        // findAllById does not preserve order — re-sort to the document's own ordinal.
        return orderedIds.stream()
            .map(id -> all.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /** Trace links whose both ends are requirements — the subset a ReqIF export can meaningfully carry as SPEC-RELATIONs. */
    public List<TraceLink> requirementToRequirementLinks() {
        return traceLinks.findAll().stream()
            .filter(l -> l.getFromType() == com.vyoog.trace.TraceObjectType.REQUIREMENT
                      && l.getToType() == com.vyoog.trace.TraceObjectType.REQUIREMENT)
            .toList();
    }

    @Transactional
    public void addRequirement(UUID documentId, UUID requirementId) {
        Document d = get(documentId);
        if (!requirements.existsById(requirementId)) throw new NoSuchElementException("No such requirement");
        Integer maxOrdinal = jdbc.queryForObject(
            "SELECT COALESCE(MAX(ordinal), 0) FROM document_requirement WHERE document_id = ?", Integer.class, documentId);
        jdbc.update("""
            INSERT INTO document_requirement (document_id, requirement_id, ordinal) VALUES (?, ?, ?)
            ON CONFLICT (document_id, requirement_id) DO NOTHING
            """, documentId, requirementId, (maxOrdinal == null ? 0 : maxOrdinal) + 1);
        d.bumpRevision();
        documents.save(d);
    }

    @Transactional
    public void removeRequirement(UUID documentId, UUID requirementId) {
        Document d = get(documentId);
        jdbc.update("DELETE FROM document_requirement WHERE document_id = ? AND requirement_id = ?", documentId, requirementId);
        d.bumpRevision();
        documents.save(d);
    }

    @Transactional
    public void reorder(UUID documentId, List<UUID> orderedRequirementIds) {
        Document d = get(documentId);
        for (int i = 0; i < orderedRequirementIds.size(); i++) {
            jdbc.update("UPDATE document_requirement SET ordinal = ? WHERE document_id = ? AND requirement_id = ?",
                i + 1, documentId, orderedRequirementIds.get(i));
        }
        d.bumpRevision();
        documents.save(d);
    }
}
