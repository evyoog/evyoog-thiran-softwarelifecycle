package com.vyoog.api.web;

import com.vyoog.identity.TeamService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** VYB-0464: teams as a real, minimal, first-class thing. */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
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

    @PostMapping
    public void create(@RequestBody CreateTeam body) {
        teams.create(body.name());
    }

    @PutMapping("/{teamId}/members/{userId}")
    public void addMember(@PathVariable UUID teamId, @PathVariable UUID userId) {
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
    public void setRole(@PathVariable UUID teamId, @PathVariable UUID userId, @RequestBody SetRole body) {
        teams.setRole(teamId, userId, body.role());
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID userId) {
        teams.removeMember(teamId, userId);
    }
}
