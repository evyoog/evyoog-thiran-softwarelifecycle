package com.vyoog.api.web;

import com.vyoog.documents.Document;
import com.vyoog.documents.DocumentReqIfExporter;
import com.vyoog.documents.DocumentService;
import com.vyoog.documents.DocumentWordExporter;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.requirements.Requirement;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** VYB-0210–0215: the document register, membership, and Word/ReqIF export. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentWordExporter wordExporter;
    private final DocumentReqIfExporter reqIfExporter;
    private final CapabilityRepository capabilities;

    public DocumentController(DocumentService service, DocumentWordExporter wordExporter,
                               DocumentReqIfExporter reqIfExporter, CapabilityRepository capabilities) {
        this.service = service;
        this.wordExporter = wordExporter;
        this.reqIfExporter = reqIfExporter;
        this.capabilities = capabilities;
    }

    public record DocumentView(String id, String key, String title, String productId, int revision, String state,
                                long itemCount, long gapCount) {}
    public record CreateDocument(@NotBlank String key, @NotBlank String title, String productId) {}
    public record RequirementSummaryView(String id, String key, String title, String statement, String capabilityId) {}

    private static DocumentView toView(DocumentService.RegisterRow row) {
        Document d = row.document();
        return new DocumentView(d.getId().toString(), d.getKey(), d.getTitle(),
            d.getProductId() == null ? null : d.getProductId().toString(), d.getRevision(), d.getState(),
            row.itemCount(), row.gapCount());
    }

    /** VYB-0210 AC1/AC2: item count, revision, gap count per row; an empty register is the caller's job to render as an empty state. */
    @GetMapping
    public List<DocumentView> register() {
        return service.register().stream().map(DocumentController::toView).toList();
    }

    @PostMapping
    public DocumentView create(@RequestBody CreateDocument body) {
        Document d = service.create(body.key(), body.title(), body.productId() == null ? null : UUID.fromString(body.productId()));
        return toView(new DocumentService.RegisterRow(d, 0, 0));
    }

    @GetMapping("/{id}/requirements")
    public List<RequirementSummaryView> requirements(@PathVariable UUID id) {
        return service.requirementsIn(id).stream().map(DocumentController::toSummary).toList();
    }

    private static RequirementSummaryView toSummary(Requirement r) {
        return new RequirementSummaryView(r.getId().toString(), r.getKey(), r.getTitle(), r.getStatement(),
            r.getCapabilityId() == null ? null : r.getCapabilityId().toString());
    }

    @PostMapping("/{id}/requirements/{requirementId}")
    public void addRequirement(@PathVariable UUID id, @PathVariable UUID requirementId) {
        service.addRequirement(id, requirementId);
    }

    @DeleteMapping("/{id}/requirements/{requirementId}")
    public void removeRequirement(@PathVariable UUID id, @PathVariable UUID requirementId) {
        service.removeRequirement(id, requirementId);
    }

    public record Reorder(List<String> orderedRequirementIds) {}

    @PutMapping("/{id}/requirements/order")
    public void reorder(@PathVariable UUID id, @RequestBody Reorder body) {
        service.reorder(id, body.orderedRequirementIds().stream().map(UUID::fromString).toList());
    }

    /** VYB-0212 AC1: a real .docx, grouped by capability. */
    @GetMapping("/{id}/export/word")
    public ResponseEntity<byte[]> exportWord(@PathVariable UUID id) {
        Document d = service.get(id);
        List<Requirement> reqs = service.requirementsIn(id);
        Map<UUID, String> capabilityNames = capabilities.findAllById(
                reqs.stream().map(Requirement::getCapabilityId).filter(java.util.Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(Capability::getId, Capability::getName));
        byte[] bytes = wordExporter.export(d, reqs, capabilityNames);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.getKey() + ".docx").build().toString())
            .body(bytes);
    }

    /** VYB-0212 AC1: round-trips through ReqIF without loss of ids or links — see DocumentReqIfExporterTest. */
    @GetMapping("/{id}/export/reqif")
    public ResponseEntity<String> exportReqIf(@PathVariable UUID id) {
        Document d = service.get(id);
        List<Requirement> reqs = service.requirementsIn(id);
        String xml = reqIfExporter.export(d, reqs, service.requirementToRequirementLinks());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.getKey() + ".reqif").build().toString())
            .body(xml);
    }
}
