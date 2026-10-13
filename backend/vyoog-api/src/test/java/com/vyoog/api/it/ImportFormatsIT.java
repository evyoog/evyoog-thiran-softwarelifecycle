package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.importqueue.ImportService;
import com.vyoog.requirements.Placement;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * VYB-0957 (F25, D44): Word and ReqIF imports, the validation that runs before an upload, and the downloadable templates, end to end
 * against a real PostgreSQL with real tokens: a file is checked, uploaded, extracted, and committed into requirements.
 */
@AutoConfigureMockMvc
class ImportFormatsIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ImportService importService;

    private Portfolio p;
    private UUID author;
    private JwtRequestPostProcessor analyst;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        analyst = tokenWith(AccessRole.BUSINESS_ANALYST);
    }

    private JwtRequestPostProcessor tokenWith(AccessRole role) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        if (role != null) grants.grant(user, role, ScopeType.APP, p.applicationId(), null, user);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private String name(String table, UUID id) {
        return jdbc.queryForObject("SELECT name FROM " + table + " WHERE id = ?", String.class, id);
    }

    private int batches() {
        return jdbc.queryForObject("SELECT count(*) FROM import_batch", Integer.class);
    }

    // ------------------------------------------------------------------ files

    private byte[] wordFile(String[]... rows) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            String[] head = {"Your Ref", "Product *", "App *", "Capability *", "Requirement Title *", "Requirement Statement *", "Type *", "Priority *", "Acceptance Criteria", "Depends On"};
            var t = doc.createTable(rows.length + 1, head.length);
            for (int c = 0; c < head.length; c++) t.getRow(0).getCell(c).setText(head[c]);
            for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length; c++) t.getRow(r + 1).getCell(c).setText(rows[r][c]);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private String[] row(String ref, String title, String type, String priority, String dependsOn) {
        return new String[] {ref, name("product", p.productId()), name("application", p.applicationId()), name("capability", p.capabilityId()),
            title, "The system shall " + title.toLowerCase() + ".", type, priority, "It works\nIt is shown", dependsOn};
    }

    private static String reqifObject(String id, String title, String text, String type, String priority) {
        return "<SPEC-OBJECT IDENTIFIER=\"" + id + "\"><VALUES>"
            + str("a-name", title) + "<ATTRIBUTE-VALUE-XHTML><DEFINITION><ATTRIBUTE-DEFINITION-XHTML-REF>a-text</ATTRIBUTE-DEFINITION-XHTML-REF></DEFINITION><THE-VALUE><xhtml:div>" + text + "</xhtml:div></THE-VALUE></ATTRIBUTE-VALUE-XHTML>"
            + str("a-type", type) + str("a-prio", priority) + "</VALUES></SPEC-OBJECT>";
    }

    private static String str(String def, String value) {
        return "<ATTRIBUTE-VALUE-STRING THE-VALUE=\"" + value + "\"><DEFINITION><ATTRIBUTE-DEFINITION-STRING-REF>" + def + "</ATTRIBUTE-DEFINITION-STRING-REF></DEFINITION></ATTRIBUTE-VALUE-STRING>";
    }

    private byte[] reqif(String objects, String relations) {
        return ("""
            <?xml version="1.0" encoding="UTF-8"?>
            <REQ-IF xmlns="http://www.omg.org/spec/ReqIF/20110401/reqif.xsd" xmlns:xhtml="http://www.w3.org/1999/xhtml"><CORE-CONTENT><REQ-IF-CONTENT>
              <SPEC-TYPES><SPEC-OBJECT-TYPE IDENTIFIER="t1"><SPEC-ATTRIBUTES>
                <ATTRIBUTE-DEFINITION-STRING IDENTIFIER="a-name" LONG-NAME="ReqIF.Name"/><ATTRIBUTE-DEFINITION-XHTML IDENTIFIER="a-text" LONG-NAME="ReqIF.Text"/>
                <ATTRIBUTE-DEFINITION-STRING IDENTIFIER="a-type" LONG-NAME="Type"/><ATTRIBUTE-DEFINITION-STRING IDENTIFIER="a-prio" LONG-NAME="Priority"/>
              </SPEC-ATTRIBUTES></SPEC-OBJECT-TYPE></SPEC-TYPES>
              <SPEC-OBJECTS>%s</SPEC-OBJECTS><SPEC-RELATIONS>%s</SPEC-RELATIONS>
            </REQ-IF-CONTENT></CORE-CONTENT></REQ-IF>
            """.formatted(objects, relations)).getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions validate(byte[] file, String filename, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(multipart("/api/v1/import/validate").file(new MockMultipartFile("file", filename, "application/octet-stream", file))
            .param("applicationId", p.applicationId().toString()).with(who));
    }

    private ResultActions upload(byte[] file, String filename, String kind, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(multipart("/api/v1/import").file(new MockMultipartFile("file", filename, "application/octet-stream", file))
            .param("applicationId", p.applicationId().toString()).param("kind", kind).with(who));
    }

    // ------------------------------------------------------------------ validation before upload

    @Test
    void VYB0957_AC15_aWordFileIsCheckedBeforeUploadAndEveryRowsProblemIsListedAtOnce() throws Exception {
        byte[] file = wordFile(
            row("R-1", "Lock the account", "SECURITY", "HIGH", ""),
            new String[] {"R-2", "No Such Product", "App", "Cap", "Bad row", "The system shall be bad.", "BANANA", "URGENT", "", ""},
            row("R-3", "Another good one", "DATA", "LOW", "R-1"));
        int before = batches();

        validate(file, "reqs.docx", analyst).andExpect(status().isOk())
            .andExpect(jsonPath("$.format").value("docx"))
            .andExpect(jsonPath("$.rows").value(3))
            .andExpect(jsonPath("$.ready").value(2))
            .andExpect(jsonPath("$.withProblems").value(1))
            .andExpect(jsonPath("$.canUpload").value(true))
            .andExpect(jsonPath("$.fileProblems.length()").value(0))
            .andExpect(jsonPath("$.rowProblems[0].ref").value("R-2"))
            .andExpect(jsonPath("$.rowProblems[0].problems[?(@ =~ /.*Type \"BANANA\".*/)]").isNotEmpty())
            .andExpect(jsonPath("$.rowProblems[0].problems[?(@ =~ /.*Priority \"URGENT\".*/)]").isNotEmpty())
            .andExpect(jsonPath("$.rowProblems[0].problems[?(@ =~ /.*No product named.*/)]").isNotEmpty());

        assertThat(batches()).as("checking a file stores nothing").isEqualTo(before);
    }

    @Test
    void VYB0957_AC16_aFileThatCannotBeReadIsReportedAsAWholeFileProblemAndCannotBeUploaded() throws Exception {
        int before = batches();
        validate(new byte[] {1, 2, 3, 4, 0, 0}, "mystery.bin", analyst).andExpect(status().isOk())
            .andExpect(jsonPath("$.canUpload").value(false))
            .andExpect(jsonPath("$.format").doesNotExist())
            .andExpect(jsonPath("$.fileProblems[0].rule").value("not-a-spreadsheet"))
            .andExpect(jsonPath("$.fileProblems[0].message").value(org.hamcrest.Matchers.containsString("ReqIF")));
        validate(new byte[0], "empty.docx", analyst).andExpect(jsonPath("$.fileProblems[0].rule").value("empty-file"));
        validate(wordFileWithoutTable(), "prose.docx", analyst).andExpect(jsonPath("$.fileProblems[0].rule").value("no-table"));

        upload(new byte[] {1, 2, 3, 4, 0, 0}, "mystery.bin", "PRD_TEMPLATE", analyst).andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("not one the import reads")));
        upload(wordFileWithoutTable(), "prose.docx", "PRD_TEMPLATE", analyst).andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("no table")));
        assertThat(batches()).as("a refused file leaves nothing in the queue").isEqualTo(before);
    }

    private byte[] wordFileWithoutTable() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("The system shall be described only in prose.");
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ Word and ReqIF through to the register

    @Test
    void VYB0957_AC17_aWordFileUploadsExtractsWithItsOwnValuesAndCommitsIntoRequirementsWithTheirLink() throws Exception {
        byte[] file = wordFile(row("W-1", "Lock the account", "SECURITY", "HIGH", ""), row("W-2", "Show the reason", "functional", "Low", "W-1"));

        String body = upload(file, "reqs.docx", "PRD_TEMPLATE", analyst).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID batch = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

        var candidates = importService.extractCandidates(batch);
        assertThat(candidates).hasSize(2);
        assertThat(candidates).allSatisfy(c -> {
            assertThat(c.getPlacement().level()).isEqualTo(Placement.capability(p.capabilityId()).level());
            assertThat(c.isTypeConfirmed()).as("the author's own Type, so confirmed, not proposed").isTrue();
            assertThat(c.isSelected()).isTrue();
            assertThat(c.getCriteriaCount()).isEqualTo((short) 2);
        });

        var outcomes = importService.commit(batch, author);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.imported()).isTrue());
        assertThat(jdbc.queryForObject("SELECT type || '/' || priority FROM requirement WHERE id = ?", String.class, outcomes.get(0).requirementId())).isEqualTo("SECURITY/HIGH");
        assertThat(jdbc.queryForObject("SELECT type || '/' || priority FROM requirement WHERE id = ?", String.class, outcomes.get(1).requirementId())).isEqualTo("FUNCTIONAL/LOW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE from_id = ? AND to_id = ? AND link_type = 'DERIVES'", Integer.class,
            outcomes.get(1).requirementId(), outcomes.get(0).requirementId())).as("W-2 depends on W-1").isEqualTo(1);
    }

    @Test
    void VYB0957_AC18_aReqIfFileKeepsItsIdentifiersTakesItsPlacementFromTheUploadAndItsRelationsBecomeLinks() throws Exception {
        byte[] file = reqif(reqifObject("REQ-A", "Lock the account", "<xhtml:p>The system shall lock an account.</xhtml:p>", "SECURITY", "HIGH")
            + reqifObject("REQ-B", "Show the reason", "The system shall show why.", "DATA", "LOW"),
            "<SPEC-RELATION IDENTIFIER=\"r1\"><SOURCE><SPEC-OBJECT-REF>REQ-B</SPEC-OBJECT-REF></SOURCE><TARGET><SPEC-OBJECT-REF>REQ-A</SPEC-OBJECT-REF></TARGET></SPEC-RELATION>");

        validate(file, "export.reqif", analyst).andExpect(status().isOk()).andExpect(jsonPath("$.format").value("reqif"))
            .andExpect(jsonPath("$.rows").value(2)).andExpect(jsonPath("$.withProblems").value(0)).andExpect(jsonPath("$.canUpload").value(true));
        String body = upload(file, "export.reqif", "PRD_TEMPLATE", analyst).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID batch = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

        var candidates = importService.extractCandidates(batch);
        assertThat(candidates).extracting(c -> c.getTag()).as("the ReqIF identifier is the tag").containsExactly("REQ-A", "REQ-B");
        assertThat(candidates.get(0).getPlacement().level()).as("no hierarchy in a ReqIF: placed in the application uploaded to").isEqualTo(Placement.application(p.applicationId()).level());
        assertThat(candidates.get(0).getStatement()).isEqualTo("The system shall lock an account.");

        importService.confirmPlacementForBatch(batch, Placement.capability(p.capabilityId()));
        var outcomes = importService.commit(batch, author);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.imported()).isTrue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE from_id = ? AND to_id = ? AND link_type = 'DERIVES'", Integer.class,
            outcomes.get(1).requirementId(), outcomes.get(0).requirementId())).as("the SPEC-RELATION became a trace link").isEqualTo(1);
    }

    @Test
    void VYB0957_AC19_aReqIfWithoutATypeOrPriorityIsRefusedWithThePlainReasonAndOldUploadKindsStayClosed() throws Exception {
        byte[] noType = reqif(reqifObject("REQ-A", "A title", "A statement.", "DATA", "LOW"), "");
        String stripped = new String(noType, StandardCharsets.UTF_8).replace("<ATTRIBUTE-DEFINITION-STRING IDENTIFIER=\"a-type\" LONG-NAME=\"Type\"/>", "")
            .replace("<ATTRIBUTE-DEFINITION-STRING IDENTIFIER=\"a-prio\" LONG-NAME=\"Priority\"/>", "");
        byte[] file = stripped.getBytes(StandardCharsets.UTF_8);

        validate(file, "export.reqif", analyst).andExpect(jsonPath("$.canUpload").value(false))
            .andExpect(jsonPath("$.fileProblems[0].rule").value("missing-columns"))
            .andExpect(jsonPath("$.fileProblems[0].message").value(org.hamcrest.Matchers.containsString("no attribute named type, priority")));
        upload(file, "export.reqif", "PRD_TEMPLATE", analyst).andExpect(status().isBadRequest());
        upload(noType, "export.reqif", "REQIF", analyst).andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("no longer accepted")));
    }

    // ------------------------------------------------------------------ who, and the templates

    @Test
    void VYB0957_AC20_onlyAPersonWhoMayCreateRequirementsHereChecksOrUploadsAFileAndAnyoneSignedInDownloadsATemplate() throws Exception {
        byte[] file = wordFile(row("R-1", "A good one", "DATA", "LOW", ""));
        int before = batches();
        for (JwtRequestPostProcessor denied : List.of(tokenWith(AccessRole.VIEWER), tokenWith(AccessRole.REVIEWER), tokenWith(null))) {
            validate(file, "reqs.docx", denied).andExpect(status().isForbidden());
            upload(file, "reqs.docx", "PRD_TEMPLATE", denied).andExpect(status().isForbidden());
        }
        mvc.perform(multipart("/api/v1/import/validate").file(new MockMultipartFile("file", "r.docx", "application/octet-stream", file))
            .param("applicationId", p.applicationId().toString())).andExpect(status().isUnauthorized());
        assertThat(batches()).isEqualTo(before);
        validate(file, "reqs.docx", tokenWith(AccessRole.ARCHITECT)).andExpect(status().isOk());

        JwtRequestPostProcessor viewer = tokenWith(AccessRole.VIEWER);
        mvc.perform(get("/api/v1/import/templates/xlsx").with(viewer)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("spreadsheetml")))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("vyoog-requirements-template.xlsx")));
        mvc.perform(get("/api/v1/import/templates/docx").with(viewer)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("wordprocessingml")));
        mvc.perform(get("/api/v1/import/templates/pdf").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/import/templates/xlsx")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0957_AC21_theDownloadedTemplatesPassTheCheckWhenFilledAndAreFoundToHaveNothingToImportWhenBlank() throws Exception {
        for (String format : List.of("xlsx", "docx")) {
            byte[] blank = mvc.perform(get("/api/v1/import/templates/" + format).with(analyst)).andReturn().getResponse().getContentAsByteArray();
            validate(blank, "blank." + format, analyst).andExpect(status().isOk()).andExpect(jsonPath("$.format").value(format))
                .andExpect(jsonPath("$.fileProblems[0].rule").value("no-rows")).andExpect(jsonPath("$.canUpload").value(false));
        }
    }
}
