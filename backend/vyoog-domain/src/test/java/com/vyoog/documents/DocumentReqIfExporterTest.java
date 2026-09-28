package com.vyoog.documents;

import static org.assertj.core.api.Assertions.*;

import com.vyoog.importqueue.ExtractedCandidate;
import com.vyoog.importqueue.ReqIfDocumentParser;
import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * VYB-0212 AC1: an actual round trip — export, then feed the output back through the
 * real importer that reads ReqIF — proving ids and links survive, not just that the
 * writer's output looks plausible.
 */
class DocumentReqIfExporterTest {

    private final DocumentReqIfExporter exporter = new DocumentReqIfExporter();
    private final ReqIfDocumentParser parser = new ReqIfDocumentParser();

    @Test
    void identifiersAndTextSurviveTheRoundTrip() {
        Document doc = new Document("DOC-1", "Test document", null);
        Requirement r1 = requirement("VY-1", "First", "Shall do the first thing.");
        Requirement r2 = requirement("VY-2", "Second", "Shall do the second thing.");

        String xml = exporter.export(doc, List.of(r1, r2), List.of());
        List<ExtractedCandidate> reimported = parser.parse(xml);

        assertThat(reimported).hasSize(2);
        assertThat(reimported.get(0).tag()).isEqualTo("VY-1");
        assertThat(reimported.get(0).text()).contains("Shall do the first thing.");
        assertThat(reimported.get(1).tag()).isEqualTo("VY-2");
    }

    @Test
    void relationsBetweenTwoDocumentRequirementsSurvive() {
        Document doc = new Document("DOC-1", "Test document", null);
        Requirement r1 = requirement("VY-1", "First", "Shall do the first thing.");
        Requirement r2 = requirement("VY-2", "Second", "Shall do the second thing.");
        TraceLink link = new TraceLink(TraceObjectType.REQUIREMENT, r1.getId(), TraceObjectType.REQUIREMENT, r2.getId(),
            TraceLinkType.SATISFIES, null);

        String xml = exporter.export(doc, List.of(r1, r2), List.of(link));
        List<ExtractedCandidate> reimported = parser.parse(xml);

        assertThat(reimported.get(0).relatedTags()).containsExactly("VY-2");
    }

    @Test
    void aLinkToARequirementNotInTheDocumentIsOmitted() {
        Document doc = new Document("DOC-1", "Test document", null);
        Requirement r1 = requirement("VY-1", "First", "Shall do the first thing.");
        TraceLink link = new TraceLink(TraceObjectType.REQUIREMENT, r1.getId(), TraceObjectType.REQUIREMENT, UUID.randomUUID(),
            TraceLinkType.SATISFIES, null);

        String xml = exporter.export(doc, List.of(r1), List.of(link));
        List<ExtractedCandidate> reimported = parser.parse(xml);

        assertThat(reimported.get(0).relatedTags()).isEmpty();
    }

    /** @GeneratedValue only assigns an id on a real INSERT — these fixtures are never persisted, so it's set by reflection instead. */
    private static Requirement requirement(String key, String title, String statement) {
        Requirement r = new Requirement(key, title, statement, null);
        setId(r, UUID.randomUUID());
        return r;
    }

    private static void setId(Requirement r, UUID id) {
        try {
            var field = Requirement.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(r, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
