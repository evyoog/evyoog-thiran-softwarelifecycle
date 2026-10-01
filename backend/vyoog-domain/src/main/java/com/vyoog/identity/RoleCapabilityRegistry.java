package com.vyoog.identity;

import java.util.List;

/**
 * VYB-0752: "generated from the authorisation model, not hand-maintained" — this is
 * that model, as a single declared list rather than a matrix typed out separately in
 * a frontend component. It states plainly, per role, whether anything in this
 * codebase actually checks it yet.
 *
 * <p>Honest inventory as of this session: {@code access_grant.role} (this enum) is
 * checked by exactly the endpoints {@link com.vyoog.api.config.PrincipalGuard
 * #requireRole}/{@code requireAdministrator} guard — all of them new this phase, all
 * under Administration. REVIEWER/APPROVER's real teeth today ("sign a review round")
 * come from a *different* column — {@code review_participant.role}, a free-text field
 * set per review round, unrelated to this grant model — noted explicitly below rather
 * than conflated with it. VYB-0902 added the first checks on BUSINESS_ANALYST, ARCHITECT
 * and APPROVER grants (seven write endpoints only; the rest is VYB-0906). VIEWER, DEVELOPER,
 * TESTER and COMPLIANCE_LEAD exist in the schema and can be granted, but no endpoint
 * restricts anything by holding them — they're informational only until a real use for
 * them is built.
 */
public final class RoleCapabilityRegistry {

    public record Capability(AccessRole role, String description, boolean enforced, String enforcedBy) {}

    public static final List<Capability> ALL = List.of(
        new Capability(AccessRole.ADMINISTRATOR, "Manage users, grants and service accounts", true,
            "PrincipalGuard.requireAdministrator — UserController/GrantController/ServiceAccountController"),
        new Capability(AccessRole.ADMINISTRATOR, "View the audit log and security findings", true,
            "PrincipalGuard.requireAdministrator — AuditController/SecurityController"),
        new Capability(AccessRole.ADMINISTRATOR, "Change tenant settings; suspend or resume the deployment", true,
            "PrincipalGuard.requireAdministrator — SettingsController"),
        new Capability(AccessRole.ADMINISTRATOR, "Create or edit products, applications, capabilities and compliance clauses (VYB-0906)", true,
            "@RequiresAccess(AccessRule.ADMIN) — ProductController, ApplicationController, CapabilityController, ClauseController"),
        new Capability(AccessRole.APPROVER, "Sign a review round as approver", true,
            "ReviewService#sign, gated on review_participant.role='APPROVER' — a separate field from this grant, see class Javadoc"),
        new Capability(AccessRole.REVIEWER, "Comment and sign a review round as reviewer", true,
            "ReviewService#sign, gated on review_participant.role='REVIEWER' — same caveat as APPROVER above"),
        new Capability(AccessRole.APPROVER, "Push a delivery brief to the planning tool (VYB-0902)", true,
            "PrincipalGuard.requireAnyRoleOrAdmin — BriefController#push, APPROVER on the brief's application"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Edit or delete a requirement; commit or delete an import batch (VYB-0902)", true,
            "PrincipalGuard.requireAnyRoleOrAdmin — RequirementController#update/delete, ImportController#commit/deleteBatch"),
        new Capability(AccessRole.ARCHITECT, "Edit or delete a requirement; commit or delete an import batch (VYB-0902)", true,
            "PrincipalGuard.requireAnyRoleOrAdmin — same endpoints as BUSINESS_ANALYST"),
        new Capability(AccessRole.REVIEWER, "Open or comment on a review; accept, dismiss or reopen a finding; review a trace link (VYB-0906)", true,
            "@RequiresAccess(AccessRule.REVIEW) — ReviewController, FindingController, TraceLinkController"),
        new Capability(AccessRole.COMPLIANCE_LEAD, "Same review actions as REVIEWER (VYB-0906)", true,
            "@RequiresAccess(AccessRule.REVIEW)"),
        new Capability(AccessRole.ARCHITECT, "Same review actions as REVIEWER (VYB-0906)", true,
            "@RequiresAccess(AccessRule.REVIEW)"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Create requirements, edit their acceptance criteria, bulk edit, raise change requests, answer clarifications, create or delete trace links (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ) — RequirementController, AcceptanceCriterionController, BulkEditController, ChangeRequestController, ClarificationController, TraceLinkController"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Author glossary terms, documents and variants; run every import step (upload, extract, analyse, edit, place, confirm), checked on the batch's application (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ) — GlossaryController, DocumentController, VariantController, ImportController"),
        new Capability(AccessRole.ARCHITECT, "Same create and edit actions as BUSINESS_ANALYST (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ)"),
        new Capability(AccessRole.VIEWER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.DEVELOPER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.TESTER, "—", false, "No endpoint checks this role yet; the Verify rule is applied to test cases and defects in VYB-0906 session 6c"));

    private RoleCapabilityRegistry() {}
}
