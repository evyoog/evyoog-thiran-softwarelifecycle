package com.vyoog.api.web;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.identity.GrantRequiredException;
import com.vyoog.identity.InvalidCredentialsException;
import com.vyoog.identity.ServiceAccountRefusedException;
import com.vyoog.identity.ServiceAccountRequiredException;
import com.vyoog.identity.StepUpRequiredException;
import com.vyoog.integration.InvalidWebhookSignatureException;
import com.vyoog.platform.RateLimitExceededException;
import com.vyoog.requirements.StaleRevisionException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 9457 problem details, so every error has a stable machine-readable shape. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * VYB-0631/0666: an uploaded document that cannot be read. Nothing caught this
     * before, so a bad file fell through to a bare 500 — or, when a parser wrapped the
     * cause in an {@link IllegalStateException}, to a 409 whose reason phrase the
     * uploader saw instead of the explanation ("Could not read this file: Conflict").
     *
     * <p>422, not 400: the request itself is well-formed — the right endpoint, the right
     * multipart shape, a real file — and it is the document's <em>content</em> that
     * cannot be processed. {@code failedRule} is carried as an extra property so the UI
     * can react to a specific failure without matching on prose.
     */
    @ExceptionHandler(com.vyoog.importqueue.DocumentValidationException.class)
    public ProblemDetail onDocumentInvalid(com.vyoog.importqueue.DocumentValidationException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/document-invalid"));
        pd.setTitle("This document could not be read");
        pd.setProperty("failedRule", e.getFailedRule());
        return pd;
    }

    /** VYB-0901: an upload the attachment policy refuses — 413 too large, 415 wrong type, 400 unusable name or empty. */
    @ExceptionHandler(com.vyoog.attachments.AttachmentRejectedException.class)
    public ProblemDetail onAttachmentRejected(com.vyoog.attachments.AttachmentRejectedException e) {
        HttpStatus status = switch (e.reason()) {
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case TYPE_NOT_ALLOWED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case BAD_FILENAME, EMPTY -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/attachment-rejected"));
        pd.setTitle("This file cannot be attached");
        return pd;
    }

    /** VYB-0901: the multipart limit in application.yml tripped before the request was even buffered. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ProblemDetail onUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
            "The upload exceeds the maximum allowed size");
        pd.setType(URI.create("https://vyoog.dev/problems/attachment-rejected"));
        pd.setTitle("This file cannot be attached");
        return pd;
    }

    /** VYB-0901: bootstrap refused (already done, an administrator exists, or not authorised). */
    @ExceptionHandler(com.vyoog.identity.BootstrapRefusedException.class)
    public ProblemDetail onBootstrapRefused(com.vyoog.identity.BootstrapRefusedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/bootstrap-refused"));
        pd.setTitle("Bootstrap refused");
        return pd;
    }

    /**
     * VYB-0928: a release move refused by its readiness gates. Same 409 as any state conflict, plus the failing gates
     * as a property so a screen can list each, and {@code overridable} to say a reason lets an approver proceed.
     */
    @ExceptionHandler(com.vyoog.release.ReleaseGateException.class)
    public ProblemDetail onReleaseGates(com.vyoog.release.ReleaseGateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/release-not-ready"));
        pd.setTitle("The release is not ready");
        pd.setProperty("failedGates", e.failed().stream().map(f -> java.util.Map.of("gate", f.gate().name(), "detail", f.detail())).toList());
        pd.setProperty("overridable", true);
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onIllegalState(IllegalStateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/illegal-state"));
        pd.setTitle("Operation not allowed in the current state");
        return pd;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> onRateLimitExceeded(RateLimitExceededException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/rate-limit-exceeded"));
        pd.setTitle("Too many requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
            .body(pd);
    }

    /**
     * VYB-0794: every AI integration in {@code com.vyoog.ai} (classification,
     * embedding, adjudication, rewrite suggestions) throws this same type for "the
     * provider couldn't be reached or isn't configured" — until now nothing here
     * caught it, so it fell through to Spring's default handling as a bare 500 with
     * no RFC 9457 shape, the exact class of bug VYB-0048b already fixed once for
     * {@code MethodArgumentNotValidException}. 503, not 500: the server itself is
     * fine — an external dependency it depends on isn't reachable or isn't set up.
     */
    @ExceptionHandler(AiProviderUnavailableException.class)
    public ProblemDetail onAiProviderUnavailable(AiProviderUnavailableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/ai-provider-unavailable"));
        pd.setTitle("AI provider unavailable");
        return pd;
    }

    /**
     * VYB-0048b: {@code @Valid} failures had no handler here, so they fell through
     * to Spring Boot's default handling — which forwards to "/error", got itself
     * rejected by Spring Security as an unauthenticated dispatch, and silently
     * became a 401 instead of the 400 this actually is. See SecurityConfig's
     * permitAll("/error") for the other half of this fix (needed regardless, for
     * any exception type that still isn't explicitly handled here).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailed(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setType(URI.create("https://vyoog.dev/problems/invalid-request"));
        pd.setTitle("Invalid request");
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/invalid-request"));
        pd.setTitle("Invalid request");
        return pd;
    }

    /**
     * Keeps the thrower's own message when it bothered to write one. Services here
     * deliberately say which thing is missing — {@code new NoSuchElementException("No such
     * developer")} — and flattening every one of those to "No such resource" threw that
     * away, leaving a caller with a 404 and no way to tell a bad developer id from a
     * deleted application. Bare {@code Optional.orElseThrow()} still lands on the generic
     * text, because there genuinely is nothing more specific to say.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail onNotFound(NoSuchElementException e) {
        String detail = e.getMessage() == null || e.getMessage().isBlank() ? "No such resource" : e.getMessage();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        pd.setType(URI.create("https://vyoog.dev/problems/not-found"));
        pd.setTitle("Not found");
        return pd;
    }

    /** VYB-0120: the response carries both the caller's and the current revision. */
    @ExceptionHandler(StaleRevisionException.class)
    public ProblemDetail onStaleRevision(StaleRevisionException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/stale-revision"));
        pd.setTitle("Someone else changed this first");
        pd.setProperty("attemptedRevision", e.getAttemptedRevision());
        pd.setProperty("currentRevision", e.getCurrentRevision());
        return pd;
    }

    /** VYB-0303 AC1: refused naming the specific level required, not a bare 401. */
    @ExceptionHandler(StepUpRequiredException.class)
    public ProblemDetail onStepUpRequired(StepUpRequiredException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/step-up-required"));
        pd.setTitle("Stronger authentication required");
        pd.setProperty("requiredLevel", e.getRequiredLevel());
        return pd;
    }

    /** VYB-0305: a service-account token on an endpoint only a person may call. */
    @ExceptionHandler(ServiceAccountRefusedException.class)
    public ProblemDetail onServiceAccountRefused(ServiceAccountRefusedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/service-account-refused"));
        pd.setTitle("Not available to a service account");
        return pd;
    }

    /** VYB-0311: a human token on the CI-only ingestion endpoints. */
    @ExceptionHandler(ServiceAccountRequiredException.class)
    public ProblemDetail onServiceAccountRequired(ServiceAccountRequiredException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/service-account-required"));
        pd.setTitle("This endpoint accepts only a service account");
        return pd;
    }

    /** VYB-0701/0703: names the specific role and scope missing, not a bare 403. */
    @ExceptionHandler(GrantRequiredException.class)
    public ProblemDetail onGrantRequired(GrantRequiredException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/grant-required"));
        pd.setTitle("Missing the required grant");
        pd.setProperty("requiredRole", e.getRole());
        return pd;
    }

    /** VYB-0741 AC1: an unsigned or wrongly-signed webhook payload is refused. */
    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ProblemDetail onInvalidWebhookSignature(InvalidWebhookSignatureException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/invalid-webhook-signature"));
        pd.setTitle("Webhook signature did not verify");
        return pd;
    }

    /** VYB-0048b: Keycloak rejected a username/password grant. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail onInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        pd.setType(URI.create("https://vyoog.dev/problems/invalid-credentials"));
        pd.setTitle("Invalid username or password");
        return pd;
    }

    /**
     * {@code DataIntegrityViolationException} covers unique violations, foreign keys, not-null
     * and check constraints alike. The old text asserted a duplicate for all of them, which
     * sent anyone debugging a foreign-key or check failure looking for a conflicting record
     * that was never there — and dropped the constraint name, the one piece of information
     * that identifies what actually failed.
     *
     * <p>Postgres puts the constraint name and its detail on the innermost cause, so that is
     * what this walks to. This is an internal tool behind authentication; a constraint name
     * in a 409 is not a disclosure worth trading a debuggable error for.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String cause = root.getMessage() == null ? "" : root.getMessage().strip();
        String detail = cause.isEmpty()
            ? "A database constraint refused this write."
            : "A database constraint refused this write: " + cause;
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        pd.setType(URI.create("https://vyoog.dev/problems/data-integrity"));
        pd.setTitle("Refused by a database constraint");
        return pd;
    }

    /** VYB-0130 AC2: an unrecognised filter/sort value is a 400 naming the field, not a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onBadParameter(MethodArgumentTypeMismatchException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "'%s' is not a valid value for '%s'".formatted(e.getValue(), e.getName()));
        pd.setType(URI.create("https://vyoog.dev/problems/invalid-request"));
        pd.setTitle("Invalid request");
        pd.setProperty("field", e.getName());
        return pd;
    }

    /** VYB-0130 AC2: sorting/filtering by a field the entity doesn't have. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail onUnknownProperty(PropertyReferenceException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "No such field: '%s'".formatted(e.getPropertyName()));
        pd.setType(URI.create("https://vyoog.dev/problems/invalid-request"));
        pd.setTitle("Invalid request");
        pd.setProperty("field", e.getPropertyName());
        return pd;
    }
}
