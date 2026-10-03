package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.TeamService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0464: teams as a real, minimal, first-class thing. */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teams;
    private final PrincipalGuard guard;

    public TeamController(TeamService teams, PrincipalGuard guard) {
        this.teams = teams;
        this.guard = guard;
    }

    /**
     * VYB-0902 (F02): changing a member's role decides who may assign the team's work (D11), and
     * removing a member takes them out of it. Only a platform administrator or a lead of that
     * team may do either; a team with no lead is administrator-only.
     */
    private void requireAdminOrTeamLead(Jwt jwt, UUID teamId, String action) {
        guard.requireAdministratorOr(jwt, uid -> "LEAD".equals(teams.roleOf(teamId, uid)),
            "Only an administrator or a lead of this team can " + action);
    }

    public record TeamView(String id, String name, List<String> memberIds) {}
    public record CreateTeam(@NotBlank String name) {}

    private static TeamView toView(TeamService.TeamWithMembers t) {
        return new TeamView(t.team().getId().toString(), t.team().getName(),
            t.memberIds().stream().map(UUID::toString).toList());
    }

    @GetMapping
    public List<TeamView> all() {
        return teams.all().stream().map(TeamController::toView).toList();
    }

    // VYB-0906: creating a team is administrator-only; managing its members is administrator or the team lead.
    @RequiresAccess(value = AccessRule.ADMIN)
    @PostMapping
    public void create(@RequestBody CreateTeam body) {
        teams.create(body.name());
    }

    @PutMapping("/{teamId}/members/{userId}")
    public void addMember(@PathVariable UUID teamId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        // VYB-0906: same rule as changing a role or removing a member (VYB-0902): an administrator or a lead of this team.
        requireAdminOrTeamLead(jwt, teamId, "add a member");
        teams.addMember(teamId, userId);
    }

    public record MemberView(String userId, String displayName, String email, String role) {}

    /** The membership list with roles — who leads this team is the question §11 rests on. */
    @GetMapping("/{teamId}/members")
    public List<MemberView> members(@PathVariable UUID teamId) {
        return teams.members(teamId).stream()
            .map(m -> new MemberView(m.userId().toString(), m.displayName(), m.email(), m.role()))
            .toList();
    }

    public record SetRole(@NotBlank String role) {}

    @PutMapping("/{teamId}/members/{userId}/role")
    public void setRole(@PathVariable UUID teamId, @PathVariable UUID userId, @RequestBody SetRole body,
                         @AuthenticationPrincipal Jwt jwt) {
        requireAdminOrTeamLead(jwt, teamId, "change a member's role");
        teams.setRole(teamId, userId, body.role());
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID userId,
                              @AuthenticationPrincipal Jwt jwt) {
        requireAdminOrTeamLead(jwt, teamId, "remove a member");
        teams.removeMember(teamId, userId);
    }
}
