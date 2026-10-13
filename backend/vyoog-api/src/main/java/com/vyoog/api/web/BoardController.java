package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.board.BoardTemplateService;
import com.vyoog.identity.TeamService;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0953 (F42, D42): board templates. Anyone signed in reads them; a team's lead or an Administrator makes and changes that team's
 * templates, and only an Administrator changes the default. The board's cards are read from the requirements list and moved with
 * the ordinary status transition, so nothing here touches a requirement.
 */
@RestController
@RequestMapping("/api/v1/boards")
public class BoardController {

    private final BoardTemplateService service;
    private final TeamService teams;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public BoardController(BoardTemplateService service, TeamService teams, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.service = service;
        this.teams = teams;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record BoardTemplateView(String id, String teamId, String teamName, String name, List<String> columns, String productId, String productName,
                                    String applicationId, String applicationName, String capabilityId, String capabilityName, String type, String priority,
                                    boolean isDefault, String updatedAt, String scopeProductId, String scopeApplicationId) {}
    public record CreateBoardTemplate(@NotBlank String teamId, @NotBlank String name, List<String> columns, String productId, String applicationId,
                                      String capabilityId, String type, String priority) {}
    public record UpdateBoardTemplate(@NotBlank String name, List<String> columns, String productId, String applicationId, String capabilityId,
                                      String type, String priority) {}
    public record BoardTemplateCreated(String id) {}

    private static String str(UUID id) { return id == null ? null : id.toString(); }

    private static UUID id(String s) { return s == null || s.isBlank() ? null : UUID.fromString(s); }

    private static BoardTemplateView view(BoardTemplateService.Template t) {
        return new BoardTemplateView(str(t.id()), str(t.teamId()), t.teamName(), t.name(), t.columns(), str(t.productId()), t.productName(),
            str(t.applicationId()), t.applicationName(), str(t.capabilityId()), t.capabilityName(), t.type(), t.priority(), t.isDefault(),
            t.updatedAt().toString(), str(t.scopeProductId()), str(t.scopeApplicationId()));
    }

    /** The default first, then each team's templates. Any signed-in person. */
    @GetMapping("/templates")
    public List<BoardTemplateView> list() {
        return service.list().stream().map(BoardController::view).toList();
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardTemplateCreated create(@RequestBody @jakarta.validation.Valid CreateBoardTemplate body, @AuthenticationPrincipal Jwt jwt) {
        UUID team = UUID.fromString(body.teamId());
        guard.requireAdministratorOr(jwt, uid -> "LEAD".equals(teams.roleOf(team, uid)), "Only an administrator or a lead of this team can make its board templates");
        return new BoardTemplateCreated(service.create(new BoardTemplateService.Fields(team, body.name(), body.columns(), id(body.productId()),
            id(body.applicationId()), id(body.capabilityId()), body.type(), body.priority()), currentUserId(jwt)).toString());
    }

    @PutMapping("/templates/{id}")
    public BoardTemplateView update(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid UpdateBoardTemplate body, @AuthenticationPrincipal Jwt jwt) {
        UUID team = service.get(id).teamId();
        if (team == null) guard.requireAdministrator(jwt);                      // the default: an Administrator only
        else guard.requireAdministratorOr(jwt, uid -> "LEAD".equals(teams.roleOf(team, uid)), "Only an administrator or a lead of this team can change its board templates");
        service.update(id, new BoardTemplateService.Fields(null, body.name(), body.columns(), id(body.productId()), id(body.applicationId()),
            id(body.capabilityId()), body.type(), body.priority()), currentUserId(jwt));
        return view(service.get(id));
    }

    @DeleteMapping("/templates/{id}")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID team = service.get(id).teamId();
        if (team == null) guard.requireAdministrator(jwt);                      // the default: an Administrator only (and it is never deleted)
        else guard.requireAdministratorOr(jwt, uid -> "LEAD".equals(teams.roleOf(team, uid)), "Only an administrator or a lead of this team can delete its board templates");
        service.delete(id, currentUserId(jwt));
    }
}
