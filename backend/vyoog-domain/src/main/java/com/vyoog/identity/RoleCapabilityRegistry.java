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
 * than conflated with it. The other six roles (VIEWER, BUSINESS_ANALYST, DEVELOPER,
 * TESTER, COMPLIANCE_LEAD, ARCHITECT) exist in the schema and can be granted, but no
 * endpoint anywhere restricts anything by holding them — they're informational only
 * until a real use for them is built.
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
        new Capability(AccessRole.APPROVER, "Sign a review round as approver", true,
            "ReviewService#sign, gated on review_participant.role='APPROVER' — a separate field from this grant, see class Javadoc"),
        new Capability(AccessRole.REVIEWER, "Comment and sign a review round as reviewer", true,
            "ReviewService#sign, gated on review_participant.role='REVIEWER' — same caveat as APPROVER above"),
        new Capability(AccessRole.VIEWER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.BUSINESS_ANALYST, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.DEVELOPER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.TESTER, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.COMPLIANCE_LEAD, "—", false, "No endpoint checks this role yet"),
        new Capability(AccessRole.ARCHITECT, "—", false, "No endpoint checks this role yet"));

    private RoleCapabilityRegistry() {}
}
