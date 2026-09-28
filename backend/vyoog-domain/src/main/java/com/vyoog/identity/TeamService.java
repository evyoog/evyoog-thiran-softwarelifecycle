package com.vyoog.identity;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Transactional;

/** VYB-0464: real teams — a name and a member list, nothing more. */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final JdbcTemplate jdbc;

    public TeamService(TeamRepository teams, JdbcTemplate jdbc) {
        this.teams = teams;
        this.jdbc = jdbc;
    }

    public record TeamWithMembers(Team team, List<UUID> memberIds) {}

    /** A membership with its role, which is what makes "who leads this team" answerable. */
    public record Member(UUID userId, String displayName, String email, String role) {}

    public List<Member> members(UUID teamId) {
        return jdbc.query("""
            SELECT m.user_id, u.display_name, u.email, m.role
            FROM team_member m JOIN app_user u ON u.id = m.user_id
            WHERE m.team_id = ?
            ORDER BY m.role, u.display_name
            """,
            (rs, n) -> new Member(UUID.fromString(rs.getString("user_id")),
                rs.getString("display_name"), rs.getString("email"), rs.getString("role")),
            teamId);
    }

    /**
     * Sets a member's role.
     *
     * @throws IllegalArgumentException if this would leave a team with members but no
     *     lead — a team nobody leads has nobody who may assign its work, which turns a
     *     routine demotion into a silent lockout.
     */
    @Transactional
    public void setRole(UUID teamId, UUID userId, String role) {
        if (!"LEAD".equals(role) && !"MEMBER".equals(role)) {
            throw new IllegalArgumentException("A team role is LEAD or MEMBER.");
        }
        if ("MEMBER".equals(role)) {
            Long leads = jdbc.queryForObject(
                "SELECT count(*) FROM team_member WHERE team_id = ? AND role = 'LEAD' AND user_id <> ?",
                Long.class, teamId, userId);
            if (leads != null && leads == 0) {
                throw new IllegalArgumentException(
                    "That is the team's only lead — make somebody else a lead first.");
            }
        }
        int updated = jdbc.update(
            "UPDATE team_member SET role = ? WHERE team_id = ? AND user_id = ?", role, teamId, userId);
        if (updated == 0) throw new java.util.NoSuchElementException("That person is not in this team.");
    }

    /** Every team this user leads. Empty for somebody who leads nothing. */
    public List<UUID> teamsLedBy(UUID userId) {
        return jdbc.queryForList(
            "SELECT team_id FROM team_member WHERE user_id = ? AND role = 'LEAD'", UUID.class, userId);
    }

    /** The leads of every team this user belongs to — who an assignment concerns besides the assignee. */
    public List<UUID> leadsFor(UUID userId) {
        return jdbc.queryForList("""
            SELECT DISTINCT lead.user_id
            FROM team_member mine
            JOIN team_member lead ON lead.team_id = mine.team_id AND lead.role = 'LEAD'
            WHERE mine.user_id = ? AND lead.user_id <> ?
            """, UUID.class, userId, userId);
    }

    /**
     * Whether {@code actor} may assign work to {@code assignee}.
     *
     * <p>Permissive where the structure is absent, strict where it exists: somebody in no
     * team, or in a team nobody leads, has nothing to check against, and refusing there
     * would make assignment impossible until every team is fully configured. Where a lead
     * does exist, only that lead may assign — the rule §11 asks for. Administrators are
     * allowed by the caller, which is where the grant check lives.
     */
    public boolean mayAssign(UUID actor, UUID assignee) {
        Long leadsOfAssigneeTeams = jdbc.queryForObject("""
            SELECT count(*) FROM team_member mine
            JOIN team_member lead ON lead.team_id = mine.team_id AND lead.role = 'LEAD'
            WHERE mine.user_id = ?
            """, Long.class, assignee);
        if (leadsOfAssigneeTeams == null || leadsOfAssigneeTeams == 0) return true;
        Long actorLeads = jdbc.queryForObject("""
            SELECT count(*) FROM team_member mine
            JOIN team_member lead ON lead.team_id = mine.team_id AND lead.role = 'LEAD'
            WHERE mine.user_id = ? AND lead.user_id = ?
            """, Long.class, assignee, actor);
        return actorLeads != null && actorLeads > 0;
    }

    /** Their role in this team, or null if they are not in it. */
    public String roleOf(UUID teamId, UUID userId) {
        return jdbc.query("SELECT role FROM team_member WHERE team_id = ? AND user_id = ?",
            rs -> rs.next() ? rs.getString(1) : null, teamId, userId);
    }


    public List<TeamWithMembers> all() {
        return teams.findAll().stream()
            .map(t -> new TeamWithMembers(t, membersOf(t.getId())))
            .toList();
    }

    private List<UUID> membersOf(UUID teamId) {
        return jdbc.query("SELECT user_id FROM team_member WHERE team_id = ?",
            (rs, n) -> UUID.fromString(rs.getString("user_id")), teamId);
    }

    @Transactional
    public Team create(String name) {
        return teams.save(new Team(name));
    }

    @Transactional
    public void addMember(UUID teamId, UUID userId) {
        if (!teams.existsById(teamId)) throw new NoSuchElementException("No such team");
        jdbc.update("INSERT INTO team_member (team_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", teamId, userId);
    }

    @Transactional
    public void removeMember(UUID teamId, UUID userId) {
        jdbc.update("DELETE FROM team_member WHERE team_id = ? AND user_id = ?", teamId, userId);
    }
}
