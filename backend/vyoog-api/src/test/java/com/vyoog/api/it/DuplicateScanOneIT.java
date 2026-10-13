package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.detectors.DuplicateAcrossApplicationsDetector;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0959: the bounded duplicate check run on every write. It was one query with an OR over both sides, which made the database
 * build every cross-application pair of embeddings first (work proportional to the square of the embeddings on every edit); it is
 * now one query per side. These tests hold what it reports: the pair is found from either side, a requirement with no similar one in
 * another application finds none, and neither a same-application pair nor a deleted requirement counts.
 */
class DuplicateScanOneIT extends IntegrationTestBase {

    @Autowired DuplicateAcrossApplicationsDetector detector;

    private String vector(double... leading) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) b.append(i == 0 ? "" : ",").append(i < leading.length ? leading[i] : 0.0);
        return b.append(']').toString();
    }

    private void embed(Requirement r, String vector) {
        jdbc.update("INSERT INTO requirement_embedding (requirement_id, revision, model, embedding) VALUES (?, 1, 'local-hashing-v1', ?::vector)", r.getId(), vector);
    }

    /** The pairs reported for {@code self} whose other side is one of {@code others}: other tests' requirements share this database. */
    private List<String> pairs(Requirement self, Requirement... others) {
        List<String> keys = java.util.Arrays.stream(others).map(Requirement::getKey).toList();
        return detector.scanOne(self.getId()).stream().map(Candidate::detail)
            .filter(d -> d.contains(self.getKey()) && keys.stream().anyMatch(d::contains)).sorted().toList();
    }

    @Test
    void VYB0959_AC1_aSimilarPairInDifferentApplicationsIsFoundFromEitherSideAndAnUnrelatedRequirementFindsNone() {
        UUID author = newUser("author");
        Portfolio one = newPortfolio(), two = newPortfolio();
        Requirement a = newRequirement(one, author), b = newRequirement(two, author), unrelated = newRequirement(two, author);
        embed(a, vector(1, 0)); embed(b, vector(1, 0.01)); embed(unrelated, vector(0, 1));

        List<String> fromA = pairs(a, b, unrelated), fromB = pairs(b, a, unrelated);

        assertThat(fromA).hasSize(1);
        assertThat(fromA.get(0)).contains(a.getKey()).contains(b.getKey());
        assertThat(fromB).as("found from the other side too, and as the same pair").isEqualTo(fromA);
        assertThat(pairs(unrelated, a, b)).isEmpty();
    }

    @Test
    void VYB0959_AC2_aSimilarPairInTheSameApplicationOrWithADeletedSideIsNotReported() {
        UUID author = newUser("author");
        Portfolio one = newPortfolio(), two = newPortfolio();
        Requirement a = newRequirement(one, author), sameApp = newRequirement(one, author), gone = newRequirement(two, author);
        embed(a, vector(1, 0)); embed(sameApp, vector(1, 0)); embed(gone, vector(1, 0));
        assertThat(pairs(a, gone)).as("a sees the one in another application").hasSize(1);
        assertThat(pairs(a, sameApp)).as("not the one in its own application").isEmpty();

        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", gone.getId());

        assertThat(pairs(a, gone)).isEmpty();
    }
}
