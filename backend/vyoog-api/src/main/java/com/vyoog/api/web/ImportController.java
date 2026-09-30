package com.vyoog.api.web;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.importqueue.DocumentAnalysis;
import com.vyoog.importqueue.DocumentAnalysisService;
import com.vyoog.importqueue.DocumentValidationException;
import com.vyoog.importqueue.ImportBatch;
import com.vyoog.importqueue.ImportCandidate;
import com.vyoog.importqueue.ImportService;
import com.vyoog.requirements.Placement;
import com.vyoog.importqueue.UploadKind;
import com.vyoog.platform.RateLimiter;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** VYB-0630–0638. */
@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    /** VYB-0782: re-extracting the same batch is the expensive repeat this guards against; VYB-0632 AC2 already made it idempotent, this just paces it. */
    private static final Duration EXTRACT_COOLDOWN = Duration.ofSeconds(5);

    /** VYB-0667: analysis reads the whole document through the model; 30s is the pace, not 5. */
    private static final Duration ANALYSE_COOLDOWN = Duration.ofSeconds(30);

    private final ImportService service;
    private final DocumentAnalysisService analysis;
    private final UserProvisioningService provisioning;
    private final RateLimiter rateLimiter;
    private final PrincipalGuard guard;

    /** VYB-0902: the matrix's "Create req" column (Business Analyst, Architect); an administrator is never blocked. */
    private static final java.util.List<AccessRole> CREATE_ROLES =
        java.util.List.of(AccessRole.BUSINESS_ANALYST, AccessRole.ARCHITECT);

    public ImportController(ImportService service, DocumentAnalysisService analysis,
                            UserProvisioningService provisioning, RateLimiter rateLimiter, PrincipalGuard guard) {
        this.service = service;
        this.analysis = analysis;
        this.provisioning = provisioning;
        this.rateLimiter = rateLimiter;
        this.guard = guard;
    }

    /**
     * VYB-0902 (F02): committing writes requirements into the register, and deleting removes an
     * upload for good. Both need "Create req" rights on the application the batch was uploaded to
     * (a grant on its product counts).
     */
    private void requireCreateRoleOnBatch(Jwt jwt, UUID batchId, String action) {
        UUID applicationId = service.getBatch(batchId).getApplicationId();
        guard.requireAnyRoleOrAdmin(jwt, CREATE_ROLES, ScopeType.APP, applicationId, action);
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record BatchView(String id, String filename, String applicationId, String uploadKind, String uploadedAt, String state) {}
    public record CandidateView(
        String id, String batchId, String tag, String statement, String originalText, String sourceLocation,
        String capabilityId, String productId, String applicationId, String placementLevel,
        boolean capabilityConfirmed, short criteriaCount, Short qualityScore,
        String flags, boolean selected, String importReason, String committedRequirementId,
        String proposedType, boolean typeConfirmed,
        // VYB-0630 AI enrichment: raw JSON, same convention as `flags` — extraction's own
        // output lives inside `flags` (briefAcceptanceCriteria/proposedTraceLinks); these
        // two are only ever non-null once a human has confirmed a list explicitly.
        String acceptedCriteria, String acceptedTraceLinks) {}

    private static BatchView toView(ImportBatch b) {
        return new BatchView(b.getId().toString(), b.getFilename(),
            b.getApplicationId() == null ? null : b.getApplicationId().toString(),
            b.getUploadKind().name(), b.getUploadedAt().toString(), b.getState());
    }

    private static CandidateView toView(ImportCandidate c) {
        return new CandidateView(c.getId().toString(), c.getBatchId().toString(), c.getTag(), c.getStatement(),
            c.getOriginalText(), c.getSourceLocation(), c.getCapabilityId() == null ? null : c.getCapabilityId().toString(),
            c.getProductId() == null ? null : c.getProductId().toString(),
            c.getApplicationId() == null ? null : c.getApplicationId().toString(),
            c.getPlacement().level().name(),
            c.isCapabilityConfirmed(), c.getCriteriaCount(), c.getQualityScore(), c.getFlags(), c.isSelected(),
            c.getImportReason(), c.getCommittedRequirementId() == null ? null : c.getCommittedRequirementId().toString(),
            c.getProposedType(), c.isTypeConfirmed(), c.getAcceptedCriteria(), c.getAcceptedTraceLinks());
    }

    /**
     * The one kind a new upload may use. Everything else read prose through the analysis
     * agents — tens of seconds per document, and a type and priority inferred rather than
     * read. EXCEL is excluded too, despite being a spreadsheet: it takes the same .xlsx and
     * reads only a statement column through that same slow path, dropping the other twenty
     * columns without saying so.
     */
    private static final java.util.Set<UploadKind> SPREADSHEET_KINDS =
        java.util.Set.of(UploadKind.PRD_TEMPLATE);

    /** VYB-0630 AC1: this writes only to import_batch — nothing enters the register. VYB-0660: a failed read names the cause. */
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView upload(@RequestParam("file") MultipartFile file, @RequestParam UUID applicationId,
                             @RequestParam UploadKind kind, @AuthenticationPrincipal Jwt jwt) {
        // VYB-0666: new uploads are spreadsheets only. Extracting requirements from prose
        // meant inferring which sentence was a requirement and what type it was, and those
        // inferences were guesses presented as data. Enforced here and not only in the
        // dropdown, because a restriction that exists only in the UI is not a restriction.
        // Reading batches uploaded under the old kinds is untouched — this refuses making
        // new ones, it does not orphan what already exists.
        if (!SPREADSHEET_KINDS.contains(kind)) {
            throw new IllegalArgumentException(
                "%s uploads are no longer accepted. Requirements are imported from the standard template — upload it as .ods, .xlsx, .xls or .csv."
                    .formatted(kind.name()));
        }
        String text;
        try {
            // VYB-0638/0666: a real .xlsx or .docx is binary — the import_batch.raw_text
            // column is TEXT, so both travel as base64 rather than reworking that
            // column's type for these kinds. Every other kind is genuine text and
            // stored as-is. A PRD template is a spreadsheet — .ods or .xlsx, both ZIP
            // containers — so it travels the same way.
            text = (kind == UploadKind.EXCEL || kind == UploadKind.WORD || kind == UploadKind.PRD_TEMPLATE)
                ? java.util.Base64.getEncoder().encodeToString(file.getBytes())
                : new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file: " + e.getMessage());
        }
        return toView(service.upload(file.getOriginalFilename(), applicationId, kind, text, currentUserId(jwt)));
    }

    @GetMapping("/batches")
    public List<BatchView> listBatches() {
        return service.listBatches().stream().map(ImportController::toView).toList();
    }

    @GetMapping("/batches/{id}")
    public BatchView getBatch(@PathVariable UUID id) {
        return toView(service.getBatch(id));
    }

    /** VYB-0666: removes an upload from the queue — a real delete, not a soft one; see {@link ImportService#deleteBatch}. */
    @DeleteMapping("/batches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBatch(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireCreateRoleOnBatch(jwt, id, "delete an import batch");
        service.deleteBatch(id, currentUserId(jwt));
    }

    /**
     * VYB-0631 AC1/VYB-0660 AC1: a validation failure names which rule, so the caller can
     * show exactly what to fix.
     *
     * <p>VYB-0667: extraction now runs the analysis agents when they are configured, so
     * it has a second class of failure — the provider being unreachable, misconfigured,
     * out of budget, or returning something unusable. That reason is passed through
     * verbatim too. Both arrive at the UI as a message next to the button rather than as
     * a generic 500, because "extraction failed" tells a user nothing they can act on.
     */
    @PostMapping("/batches/{id}/extract")
    public List<CandidateView> extract(@PathVariable UUID id) {
        try {
            return service.extractCandidates(id).stream().map(ImportController::toView).toList();
        } catch (DocumentValidationException e) {
            throw new IllegalArgumentException(e.getFailedRule() + ": " + e.getMessage());
        } catch (AiProviderUnavailableException e) {
            throw new IllegalStateException("Document analysis failed, so nothing was extracted: " + e.getMessage());
        }
    }

    @GetMapping("/batches/{id}/candidates")
    public List<CandidateView> candidates(@PathVariable UUID id) {
        return service.forBatch(id).stream().map(ImportController::toView).toList();
    }

    @PostMapping("/candidates/{id}/lint")
    public CandidateView lint(@PathVariable UUID id) {
        return toView(service.lint(id));
    }

    @PostMapping("/candidates/{id}/propose-capability")
    public CandidateView proposeCapability(@PathVariable UUID id, @RequestParam UUID applicationId) {
        return toView(service.proposeCapability(id, applicationId));
    }

    public record ConfirmType(@NotBlank String type) {}

    @PostMapping("/candidates/{id}/confirm-type")
    public CandidateView confirmType(@PathVariable UUID id, @RequestBody ConfirmType body) {
        return toView(service.confirmType(id, body.type()));
    }

    public record ConfirmAcceptanceCriteria(List<String> criteria) {}

    @PostMapping("/candidates/{id}/confirm-acceptance-criteria")
    public CandidateView confirmAcceptanceCriteria(@PathVariable UUID id, @RequestBody ConfirmAcceptanceCriteria body) {
        return toView(service.confirmAcceptanceCriteria(id, body.criteria() == null ? List.of() : body.criteria()));
    }

    /** VYB-0630 AI enrichment: an honest failure — the reason is named, never swallowed. */
    @PostMapping("/candidates/{id}/propose-trace-links")
    public CandidateView proposeTraceLinks(@PathVariable UUID id) {
        try {
            return toView(service.proposeTraceLinks(id));
        } catch (AiProviderUnavailableException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    public record TraceLinkChoiceBody(@NotBlank String requirementId, @NotBlank String linkType) {}
    public record ConfirmTraceLinks(List<TraceLinkChoiceBody> links) {}

    @PostMapping("/candidates/{id}/confirm-trace-links")
    public CandidateView confirmTraceLinks(@PathVariable UUID id, @RequestBody ConfirmTraceLinks body) {
        List<ImportService.TraceLinkChoice> choices = (body.links() == null ? List.<TraceLinkChoiceBody>of() : body.links())
            .stream()
            .map(l -> new ImportService.TraceLinkChoice(UUID.fromString(l.requirementId()), l.linkType()))
            .toList();
        return toView(service.confirmTraceLinks(id, choices));
    }

    public record EditText(@NotBlank String statement) {}

    /** VYB-0635/0662: edits the working copy only — {@code originalText} never moves. */
    @PatchMapping("/candidates/{id}")
    public CandidateView edit(@PathVariable UUID id, @RequestBody EditText body) {
        return toView(service.editText(id, body.statement()));
    }

    public record ConfirmCapability(@NotBlank String capabilityId) {}

    /** D12: at most one of the three; none clears the placement back to unplaced. */
    public record ConfirmPlacement(String productId, String applicationId, String capabilityId) {}

    @PostMapping("/candidates/{id}/placement")
    public CandidateView confirmPlacement(@PathVariable UUID id, @RequestBody ConfirmPlacement body) {
        return toView(service.confirmPlacement(id, placementOf(body)));
    }

    @PostMapping("/batches/{batchId}/placement")
    public java.util.Map<String, Integer> confirmBatchPlacement(@PathVariable UUID batchId,
                                                                 @RequestBody ConfirmPlacement body) {
        return java.util.Map.of("placed", service.confirmPlacementForBatch(batchId, placementOf(body)));
    }

    private static Placement placementOf(ConfirmPlacement body) {
        return Placement.of(uuidOrNull(body.productId()), uuidOrNull(body.applicationId()),
            uuidOrNull(body.capabilityId()));
    }

    private static UUID uuidOrNull(String s) {
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    @PostMapping("/candidates/{id}/confirm-capability")
    public CandidateView confirmCapability(@PathVariable UUID id, @RequestBody ConfirmCapability body) {
        return toView(service.confirmCapability(id, UUID.fromString(body.capabilityId())));
    }

    public record SetSelected(boolean selected) {}

    /** VYB-0663: accept/import-as-written both just mean "selected"; skip means leaving this false. */
    @PostMapping("/candidates/{id}/select")
    public CandidateView select(@PathVariable UUID id, @RequestBody SetSelected body) {
        return toView(service.select(id, body.selected()));
    }

    public record SetImportReason(@NotBlank String reason) {}

    @PutMapping("/candidates/{id}/import-reason")
    public CandidateView setImportReason(@PathVariable UUID id, @RequestBody SetImportReason body) {
        return toView(service.setImportReason(id, body.reason()));
    }

    // ── VYB-0667: document analysis ───────────────────────────────────────────

    /**
     * Everything the reviewer needs to judge the proposal, including what the run did
     * not manage to read and what the critic could not tie back to the document.
     * {@code findings}, {@code themes} and {@code unsupportedClaims} are passed through
     * as raw JSON strings — they are stored as jsonb and the client parses them, the
     * same way it already parses a candidate's {@code flags}.
     */
    public record AnalysisView(
        String id, String batchId, String state, String description,
        String findings, String themes, String unsupportedClaims,
        int chunksTotal, int chunksAnalysed, int findingsKept, int findingsRejected,
        int noiseBlocksDiscarded, boolean revisionRan, boolean partial,
        String model, String promptVersion, int aiCalls,
        String createdAt, String decidedAt, String dismissReason) {}

    private static AnalysisView toView(DocumentAnalysis a) {
        return new AnalysisView(
            a.getId().toString(), a.getBatchId().toString(), a.getState(), a.getDescription(),
            a.getFindings(), a.getThemes(), a.getUnsupportedClaims(),
            a.getChunksTotal(), a.getChunksAnalysed(), a.getFindingsKept(), a.getFindingsRejected(),
            a.getNoiseBlocksDiscarded(), a.isRevisionRan(), a.isPartial(),
            a.getModel(), a.getPromptVersion(), a.getAiCalls(),
            a.getCreatedAt().toString(),
            a.getDecidedAt() == null ? null : a.getDecidedAt().toString(),
            a.getDismissReason());
    }

    @PostMapping("/batches/{id}/analyse")
    public AnalysisView analyse(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = currentUserId(jwt);
        if (rateLimiter.checkAndRecord("import.analyse:" + id, ANALYSE_COOLDOWN) != null) {
            throw new IllegalStateException("This document was analysed a moment ago. Wait before running it again.");
        }
        try {
            return toView(analysis.analyse(id, actor));
        } catch (AiProviderUnavailableException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    /** The current proposal, or 204 when this batch has never been analysed. */
    @GetMapping("/batches/{id}/analysis")
    public ResponseEntity<AnalysisView> analysis(@PathVariable UUID id) {
        DocumentAnalysis current = analysis.current(id);
        return current == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(toView(current));
    }

    /** Earlier runs, newest first — a re-analysis does not erase what an earlier decision was made against. */
    @GetMapping("/batches/{id}/analysis/history")
    public List<AnalysisView> analysisHistory(@PathVariable UUID id) {
        return analysis.history(id).stream().map(ImportController::toView).toList();
    }

    @PostMapping("/analysis/{id}/accept")
    public AnalysisView acceptAnalysis(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return toView(analysis.accept(id, currentUserId(jwt)));
    }

    public record DismissAnalysis(@NotBlank String reason) {}

    @PostMapping("/analysis/{id}/dismiss")
    public AnalysisView dismissAnalysis(@PathVariable UUID id, @RequestBody DismissAnalysis body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return toView(analysis.dismiss(id, currentUserId(jwt), body.reason()));
    }

    public record CommitOutcomeView(String candidateId, boolean imported, String reason, String requirementId) {}

    /** VYB-0636/0665: only selected+confirmed candidates commit; everything else states exactly why it didn't. */
    @PostMapping("/batches/{id}/commit")
    public List<CommitOutcomeView> commit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireCreateRoleOnBatch(jwt, id, "commit an import batch");
        return service.commit(id, currentUserId(jwt)).stream()
            .map(o -> new CommitOutcomeView(o.candidateId().toString(), o.imported(), o.reason(),
                o.requirementId() == null ? null : o.requirementId().toString()))
            .toList();
    }
}
