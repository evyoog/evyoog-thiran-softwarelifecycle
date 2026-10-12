package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.design.DesignEdge;
import com.vyoog.design.DesignFlow;
import com.vyoog.design.DesignNode;
import com.vyoog.design.DesignService;
import com.vyoog.design.DesignService.Placement;
import com.vyoog.design.NodeKind;
import com.vyoog.identity.AccessRole;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0945 (F20): editing a design flow: rename or re-kind a step, relabel or remove a connection, and save or forget a dragged layout.
 * Against a real PostgreSQL, service calls and HTTP with real tokens.
 */
@AutoConfigureMockMvc
class DesignEditingIT extends IntegrationTestBase {

    @Autowired DesignService design;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author;
    private DesignFlow flow;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        flow = design.createFlow(p.applicationId(), author);
    }

    private DesignNode step(String label) {
        return design.addNode(flow.getId(), NodeKind.step, label, null, author);
    }

    private int xOf(UUID node) {
        return jdbc.queryForObject("SELECT x FROM design_node WHERE id = ?", Integer.class, node);
    }

    @Test
    void VYB0945_AC1_aStepCanBeRenamedReKindedAndReNotedAndTheChangeIsAudited() {
        DesignNode n = step("Old name");
        DesignNode edited = design.updateNode(n.getId(), NodeKind.decision, "  New name  ", "a note", author);

        assertThat(edited.getLabel()).isEqualTo("New name");
        assertThat(edited.getKind()).isEqualTo(NodeKind.decision);
        assertThat(edited.getNote()).isEqualTo("a note");
        assertThat(auditCount(n.getId(), "design.node_edited")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT before->>'label' FROM audit_event WHERE object_id = ? AND action = 'design.node_edited'",
            String.class, n.getId())).isEqualTo("Old name");

        assertThat(design.updateNode(n.getId(), NodeKind.decision, "New name", "  ", author).getNote()).isNull();
    }

    @Test
    void VYB0945_AC1_aBlankOrTooLongLabelIsRefusedAndNothingChanges() {
        DesignNode n = step("Keep me");
        assertThatThrownBy(() -> design.updateNode(n.getId(), NodeKind.step, "   ", null, author)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> design.updateNode(n.getId(), NodeKind.step, "x".repeat(201), null, author)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> design.updateNode(UUID.randomUUID(), NodeKind.step, "Nobody", null, author)).isInstanceOf(NoSuchElementException.class);
        assertThat(jdbc.queryForObject("SELECT label FROM design_node WHERE id = ?", String.class, n.getId())).isEqualTo("Keep me");
        assertThat(auditCount(n.getId(), "design.node_edited")).isZero();
    }

    @Test
    void VYB0945_AC2_aPipelineMilestoneMayBeRenamedButKeepsItsKindAndNoStepBecomesOne() {
        DesignNode testing = design.addNode(flow.getId(), NodeKind.testing, "Testing", null, author);
        assertThat(design.updateNode(testing.getId(), NodeKind.testing, "QA gate", null, author).getLabel()).isEqualTo("QA gate");
        assertThatThrownBy(() -> design.updateNode(testing.getId(), NodeKind.step, "QA gate", null, author))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("keeps its kind");
        assertThatThrownBy(() -> design.updateNode(testing.getId(), NodeKind.deployment, "QA gate", null, author))
            .isInstanceOf(IllegalStateException.class);
        DesignNode plain = step("Plain");
        assertThatThrownBy(() -> design.updateNode(plain.getId(), NodeKind.deployment, "Plain", null, author))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be made");
    }

    @Test
    void VYB0945_AC3_anEdgeCanBeRelabelledAndItsLabelCleared() {
        DesignNode a = step("A"), b = step("B");
        DesignEdge e = design.addEdge(flow.getId(), a.getId(), b.getId(), "yes", author);

        assertThat(design.relabelEdge(e.getId(), "  no  ", author).getLabel()).isEqualTo("no");
        assertThat(auditCount(e.getId(), "design.edge_relabelled")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT before->>'label' FROM audit_event WHERE object_id = ? AND action = 'design.edge_relabelled'",
            String.class, e.getId())).isEqualTo("yes");
        assertThat(design.relabelEdge(e.getId(), "", author).getLabel()).isNull();
        assertThatThrownBy(() -> design.relabelEdge(e.getId(), "x".repeat(101), author)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> design.relabelEdge(UUID.randomUUID(), "x", author)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0945_AC4_removingAnEdgeLeavesBothStepsAndIsAudited() {
        DesignNode a = step("A"), b = step("B");
        DesignEdge e = design.addEdge(flow.getId(), a.getId(), b.getId(), null, author);

        design.deleteEdge(e.getId(), author);

        assertThat(design.edgesIn(flow.getId())).isEmpty();
        assertThat(design.nodesIn(flow.getId())).hasSize(2);
        assertThat(auditCount(e.getId(), "design.edge_deleted")).isEqualTo(1);
        assertThatThrownBy(() -> design.deleteEdge(e.getId(), author)).isInstanceOf(NoSuchElementException.class);
        // and the same two steps can be joined again
        design.addEdge(flow.getId(), a.getId(), b.getId(), "again", author);
    }

    @Test
    void VYB0945_AC5_aSavedLayoutIsKeptOnTheStepsAndAuditedAsOneAction() {
        DesignNode a = step("A"), b = step("B"), c = step("C");
        assertThat(design.nodesIn(flow.getId())).allSatisfy(n -> { assertThat(n.getX()).isNull(); assertThat(n.getY()).isNull(); });

        design.saveLayout(flow.getId(), List.of(new Placement(a.getId(), 40, 50), new Placement(b.getId(), 300, 90)), author);

        assertThat(xOf(a.getId())).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT y FROM design_node WHERE id = ?", Integer.class, b.getId())).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT x FROM design_node WHERE id = ?", Integer.class, c.getId())).isNull();   // never placed: stays automatic
        assertThat(auditCount(flow.getId(), "design.layout_saved")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT (after->>'moved')::int FROM audit_event WHERE object_id = ? AND action = 'design.layout_saved'",
            Integer.class, flow.getId())).isEqualTo(2);

        design.saveLayout(flow.getId(), List.of(new Placement(a.getId(), 70, 80)), author);   // moving again replaces, it does not add
        assertThat(xOf(a.getId())).isEqualTo(70);
        assertThat(xOf(b.getId())).isEqualTo(300);
    }

    @Test
    void VYB0945_AC6_aLayoutThatIsWrongInAnyWayIsRefusedWholeAndMovesNothing() {
        DesignNode a = step("A"), b = step("B");
        design.saveLayout(flow.getId(), List.of(new Placement(a.getId(), 10, 10)), author);

        Portfolio other = newPortfolio();
        DesignFlow otherFlow = design.createFlow(other.applicationId(), author);
        DesignNode foreign = design.addNode(otherFlow.getId(), NodeKind.step, "Foreign", null, author);

        assertThatThrownBy(() -> design.saveLayout(flow.getId(), List.of(new Placement(b.getId(), 500, 500), new Placement(foreign.getId(), 1, 1)), author))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in this flow");
        assertThatThrownBy(() -> design.saveLayout(flow.getId(), List.of(new Placement(b.getId(), 1, 1), new Placement(b.getId(), 2, 2)), author))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> design.saveLayout(flow.getId(), List.of(new Placement(b.getId(), -1, 5)), author))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> design.saveLayout(flow.getId(), List.of(new Placement(b.getId(), 100_001, 5)), author))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> design.saveLayout(flow.getId(), List.of(), author)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> design.saveLayout(UUID.randomUUID(), List.of(new Placement(a.getId(), 1, 1)), author))
            .isInstanceOf(NoSuchElementException.class);

        assertThat(jdbc.queryForObject("SELECT x FROM design_node WHERE id = ?", Integer.class, b.getId())).isNull();
        assertThat(xOf(a.getId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT x FROM design_node WHERE id = ?", Integer.class, foreign.getId())).isNull();
    }

    @Test
    void VYB0945_AC7_resettingForgetsEveryPlaceAndKeepsEverythingElse() {
        DesignNode a = step("A"), b = step("B");
        design.addEdge(flow.getId(), a.getId(), b.getId(), "go", author);
        design.saveLayout(flow.getId(), List.of(new Placement(a.getId(), 40, 50), new Placement(b.getId(), 90, 60)), author);

        assertThat(design.resetLayout(flow.getId(), author)).isEqualTo(2);

        assertThat(design.nodesIn(flow.getId())).allSatisfy(n -> { assertThat(n.getX()).isNull(); assertThat(n.getY()).isNull(); });
        assertThat(design.nodesIn(flow.getId())).extracting(DesignNode::getLabel).containsExactlyInAnyOrder("A", "B");
        assertThat(design.edgesIn(flow.getId())).extracting(DesignEdge::getLabel).containsExactly("go");
        assertThat(auditCount(flow.getId(), "design.layout_reset")).isEqualTo(1);
        assertThat(design.resetLayout(flow.getId(), author)).isZero();   // nothing left to forget, and it says so
    }

    @Test
    void VYB0945_AC8_theDatabaseRefusesAHalfPlacedStepAndAPlaceOutsideTheRange() {
        DesignNode a = step("A");
        assertThatThrownBy(() -> jdbc.update("UPDATE design_node SET x = 5 WHERE id = ?", a.getId())).hasMessageContaining("design_node_position_both_or_neither");
        assertThatThrownBy(() -> jdbc.update("UPDATE design_node SET x = -1, y = 0 WHERE id = ?", a.getId())).hasMessageContaining("design_node_position_range");
    }

    @Test
    void VYB0945_AC9_generatingAgainKeepsADraggedLayoutAndLeavesNewStepsUnplaced() {
        Requirement r1 = approved(newRequirement(p, author), author, newAdministrator());
        design.generateFrom(flow.getId(), p.applicationId(), author);
        DesignNode drawn = design.nodesIn(flow.getId()).stream().filter(n -> n.getLabel().startsWith(r1.getKey())).findFirst().orElseThrow();
        design.saveLayout(flow.getId(), List.of(new Placement(drawn.getId(), 600, 400)), author);

        Requirement r2 = approved(newRequirement(p, author), author, newAdministrator());
        design.generateFrom(flow.getId(), p.applicationId(), author);

        assertThat(xOf(drawn.getId())).isEqualTo(600);
        DesignNode added = design.nodesIn(flow.getId()).stream().filter(n -> n.getLabel().startsWith(r2.getKey())).findFirst().orElseThrow();
        assertThat(added.getX()).isNull();
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        if (role != null) grantOnCapability(user, role, p.capabilityId());
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0945_AC10_whoMayEditOverHttpAnAuthorDoesAViewerAndAnUnknownPersonDoNotAndAnyoneMayRead() throws Exception {
        DesignNode a = step("A"), b = step("B");
        DesignEdge e = design.addEdge(flow.getId(), a.getId(), b.getId(), "yes", author);
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);
        JwtRequestPostProcessor nobody = aPersonWith(null);
        String json = MediaType.APPLICATION_JSON_VALUE;
        String editBody = "{\"kind\":\"step\",\"label\":\"Renamed\",\"note\":\"n\"}";
        String layoutBody = "{\"placements\":[{\"nodeId\":\"" + a.getId() + "\",\"x\":120,\"y\":60}]}";

        for (JwtRequestPostProcessor denied : List.of(viewer, nobody)) {
            mvc.perform(patch("/api/v1/design/nodes/" + a.getId()).with(denied).contentType(json).content(editBody)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/design/flows/" + flow.getId() + "/layout").with(denied).contentType(json).content(layoutBody)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/design/flows/" + flow.getId() + "/layout").with(denied)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/design/edges/" + e.getId()).with(denied).contentType(json).content("{\"label\":\"x\"}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/design/edges/" + e.getId()).with(denied)).andExpect(status().isForbidden());
        }
        mvc.perform(patch("/api/v1/design/nodes/" + a.getId()).contentType(json).content(editBody)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT label FROM design_node WHERE id = ?", String.class, a.getId())).isEqualTo("A");

        mvc.perform(patch("/api/v1/design/nodes/" + a.getId()).with(analyst).contentType(json).content(editBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Renamed"));
        mvc.perform(put("/api/v1/design/flows/" + flow.getId() + "/layout").with(analyst).contentType(json).content(layoutBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/v1/design/flows/" + flow.getId() + "/nodes").with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.label == 'Renamed')].x").value(120)).andExpect(jsonPath("$[?(@.label == 'Renamed')].y").value(60))
            .andExpect(jsonPath("$[?(@.label == 'B')].x").value((Object) null));
        mvc.perform(patch("/api/v1/design/edges/" + e.getId()).with(analyst).contentType(json).content("{\"label\":\"no\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("no"));
        mvc.perform(delete("/api/v1/design/flows/" + flow.getId() + "/layout").with(analyst)).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mvc.perform(delete("/api/v1/design/edges/" + e.getId()).with(analyst)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/design/edges/" + e.getId()).with(analyst)).andExpect(status().isNotFound());
    }

    @Test
    void VYB0945_AC10_badRequestsAreRefusedWithAReason() throws Exception {
        DesignNode a = step("A");
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST);
        String json = MediaType.APPLICATION_JSON_VALUE;
        mvc.perform(patch("/api/v1/design/nodes/" + a.getId()).with(analyst).contentType(json).content("{\"kind\":\"step\",\"label\":\"\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/design/nodes/" + a.getId()).with(analyst).contentType(json).content("{\"kind\":\"nonsense\",\"label\":\"x\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/design/flows/" + flow.getId() + "/layout").with(analyst).contentType(json).content("{\"placements\":[]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/design/flows/" + flow.getId() + "/layout").with(analyst).contentType(json)
            .content("{\"placements\":[{\"nodeId\":\"" + a.getId() + "\",\"x\":-5,\"y\":0}]}")).andExpect(status().isBadRequest());
    }
}
