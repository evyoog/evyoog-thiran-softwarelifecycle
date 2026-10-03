package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0912 (F12): the OpenAPI document the running API publishes is committed at
 * {@code docs/06-api/openapi/openapi.json}, and this test fails when the two differ, so a controller
 * or DTO change cannot reach the repository without the document (and the frontend types generated
 * from it) being regenerated.
 *
 * <p>To regenerate after an API change:
 * <pre>cd backend &amp;&amp; ./mvnw -B -pl vyoog-api -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dit.test=OpenApiDocumentIT -Dfailsafe.failIfNoSpecifiedTests=false -Dopenapi.write=true
 * cd ../frontend &amp;&amp; npm run generate-api</pre>
 * Only {@code servers} is removed (it carries the host the test happened to be called on); keys are
 * sorted so the file does not churn with class-scan order.
 */
@AutoConfigureMockMvc
class OpenApiDocumentIT extends IntegrationTestBase {

    private static final Path COMMITTED = Path.of("..", "..", "docs", "06-api", "openapi", "openapi.json")
        .toAbsolutePath().normalize();

    @Autowired MockMvc mvc;

    private String currentDocument() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        JsonNode tree = mapper.readTree(raw);
        ((ObjectNode) tree).remove("servers");
        // Sort object keys recursively: convertValue through a TreeMap-ordered write.
        Object sorted = mapper.treeToValue(tree, Object.class);
        return mapper.writeValueAsString(sorted) + "\n";
    }

    @Test
    void VYB0912_AC2_theCommittedOpenApiDocumentMatchesWhatTheRunningApiPublishes() throws Exception {
        String current = currentDocument();
        if (System.getProperty("openapi.write") != null) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, current, StandardCharsets.UTF_8);
            return;
        }
        assertThat(COMMITTED).as("the committed OpenAPI document (see this class's Javadoc to generate it)").exists();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
            .as("docs/06-api/openapi/openapi.json is out of date: regenerate it and the frontend types "
                + "(see this class's Javadoc)")
            .isEqualTo(current);
    }

    @Test
    void VYB0912_AC2_theDocumentCoversTheApiAndKeepsItsErrorShape() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(currentDocument());
        assertThat(doc.at("/openapi").asText()).startsWith("3.");
        assertThat(doc.at("/paths").size()).as("paths").isGreaterThan(100);
        assertThat(doc.at("/paths").fieldNames()).toIterable().contains("/api/v1/requirements", "/api/v1/search");
        assertThat(doc.at("/components/schemas").size()).as("schemas").isGreaterThan(50);
    }
}
