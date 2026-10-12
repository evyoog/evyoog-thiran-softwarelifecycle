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
 * under Administration. Review rounds, whose signing used a *different* column
 * ({@code review_participant.role}, set per round), were retired in D26: that column is
 * read-only history now, and REVIEWER/APPROVER mean only what the endpoints below say. VYB-0902 added the first checks on BUSINESS_ANALYST, ARCHITECT
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
        new Capability(AccessRole.ADMINISTRATOR, "Create deployment environments and teams; re-embed stale requirements (VYB-0906)", true,
            "@RequiresAccess(AccessRule.ADMIN) — EnvironmentController, TeamController#create, AiController"),
        new Capability(AccessRole.APPROVER, "Create a release, set its target date, commit or remove scope (VYB-0906)", true,
            "@RequiresAccess(AccessRule.BASELINE) — ReleaseController"),
        new Capability(AccessRole.APPROVER, "Push a delivery brief to the planning tool (VYB-0902)", true,
            "PrincipalGuard.requireAnyRoleOrAdmin — BriefController#push, APPROVER on the brief's application"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Edit or delete a requirement; commit or delete an import batch (VYB-0902)", true,
            "PrincipalGuard.requireAnyRoleOrAdmin — RequirementController#update/delete, ImportController#commit/deleteBatch"),
        new Capability(AccessRole.ARCHITECT, "Edit or delete a requirement; commit or delete an import batch (VYB-0902)", true,
            "PrincipalGuard.requireAnyRoleOrAdmin — same endpoints as BUSINESS_ANALYST"),
        new Capability(AccessRole.REVIEWER, "Accept, dismiss or reopen a finding; review a trace link (VYB-0906)", true,
            "@RequiresAccess(AccessRule.REVIEW) — FindingController, TraceLinkController"),
        new Capability(AccessRole.COMPLIANCE_LEAD, "Same review actions as REVIEWER (VYB-0906)", true,
            "@RequiresAccess(AccessRule.REVIEW)"),
        new Capability(AccessRole.ARCHITECT, "Same review actions as REVIEWER (VYB-0906)", true,
            "@RequiresAccess(AccessRule.REVIEW)"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Create requirements, edit their acceptance criteria, bulk edit, raise change requests, answer clarifications, create or delete trace links (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ) — RequirementController, AcceptanceCriterionController, BulkEditController, ChangeRequestController, ClarificationController, TraceLinkController"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Author design flows; generate a delivery brief (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ) — DesignController, BriefController#generate"),
        new Capability(AccessRole.BUSINESS_ANALYST, "Author glossary terms, documents and variants; run every import step (upload, extract, analyse, edit, place, confirm), checked on the batch's application (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ) — GlossaryController, DocumentController, VariantController, ImportController"),
        new Capability(AccessRole.ARCHITECT, "Same create and edit actions as BUSINESS_ANALYST (VYB-0906)", true,
            "@RequiresAccess(AccessRule.CREATE_EDIT_REQ)"),
        new Capability(AccessRole.VIEWER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.DEVELOPER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.TESTER, "Raise, classify and close defects; draft and update test cases (VYB-0906)", true,
            "@RequiresAccess(AccessRule.VERIFY) — DefectController, TestCaseController"));

    private RoleCapabilityRegistry() {}
}
