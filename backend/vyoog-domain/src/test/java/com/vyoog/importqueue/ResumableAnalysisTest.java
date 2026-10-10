package com.vyoog.importqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.DocumentDescriptionSynthesizer;
import com.vyoog.ai.DocumentGroundingCritic;
import com.vyoog.ai.DocumentRelevanceTriager;
import com.vyoog.ai.RequirementBriefAnalyst;
import com.vyoog.importqueue.DocumentAnalysisServiceTest.FakeBriefAnalyst;
import com.vyoog.importqueue.DocumentAnalysisServiceTest.FakeCritic;
import com.vyoog.importqueue.DocumentAnalysisServiceTest.FakeSynthesizer;
import com.vyoog.importqueue.DocumentAnalysisServiceTest.FakeTriager;
import com.vyoog.platform.audit.AuditService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * VYB-0940 (F31): an AI extraction is many model calls. A run that fails part way is made again and continues from the steps it
 * had finished: it does not call the model again for them, does not spend the per-run budget on them, and ends with exactly
 * what an uninterrupted run would have produced. The agents are fakes; what is tested is the orchestration.
 */
class ResumableAnalysisTest {

    /** Steps kept in memory, but through JSON exactly as the database ones are, so what is saved can really be read back. */
    static final class MemorySteps implements ExtractionSteps {
        private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        final Map<String, String> saved = new HashMap<>();

        @Override public <T> Optional<T> load(String key, Class<T> type) {
            String text = saved.get(key);
            if (text == null) return Optional.empty();
            try {
                return Optional.of(json.readValue(text, type));
            } catch (Exception e) {
                throw new AssertionError("a saved step could not be read back: " + key, e);
            }
        }

        @Override public <T> void save(String key, T value) {
            try {
                saved.put(key, json.writeValueAsString(value));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    /** A triager that fails on one numbered call (1-based) and otherwise behaves as the real fake does. */
    static final class FlakyTriager implements DocumentRelevanceTriager {
        final FakeTriager inner = new FakeTriager();
        int failOnCall = -1;
        int calls;

        @Override public Triage triage(String chunkText, String sourceLocation) {
            calls++;
            if (calls == failOnCall) throw new AiProviderUnavailableException("provider down on call " + calls);
            return inner.triage(chunkText, sourceLocation);
        }

        @Override public boolean available() { return true; }
        @Override public String modelName() { return "fake-model-1"; }
    }

    /** A critic that fails on its first call when asked. */
    static final class FlakyCritic implements DocumentGroundingCritic {
        boolean failNext;
        int calls;

        @Override public Critique critique(String description, List<com.vyoog.ai.DocumentFinding> findings) {
            calls++;
            if (failNext) {
                failNext = false;
                throw new AiProviderUnavailableException("provider down in the check");
            }
            return new Critique(List.of(), "");
        }

        @Override public String modelName() { return "fake-model-1"; }
    }

    /** A brief analyst that fails on one numbered call (1-based). */
    static final class FlakyBriefs implements RequirementBriefAnalyst {
        final FakeBriefAnalyst inner = new FakeBriefAnalyst();
        int failOnCall = -1;
        int calls;

        @Override public List<Brief> analyse(String filename, String description, List<com.vyoog.ai.DocumentFinding> findings) {
            calls++;
            if (calls == failOnCall) throw new AiProviderUnavailableException("provider down on brief call " + calls);
            return inner.analyse(filename, description, findings);
        }

        @Override public boolean available() { return true; }
        @Override public String modelName() { return "fake-brief-model"; }
    }

    private static String padded(String clause) {
        return clause + " " + "x".repeat(2800) + ".";
    }

    /** Eight paragraphs, one numbered clause each; several chunks, and more than one batch of six briefs. */
    private static final String DOC = String.join("\n\n", java.util.stream.IntStream.rangeClosed(1, 8)
        .mapToObj(i -> padded("The system shall clause number " + i)).toList());

    private final AiUsageTracker usage = mock(AiUsageTracker.class);
    private final FlakyTriager triager = new FlakyTriager();
    private final FakeSynthesizer synthesizer = new FakeSynthesizer();
    private final FlakyCritic critic = new FlakyCritic();
    private final FlakyBriefs briefs = new FlakyBriefs();
    private DocumentAnalysisService service;
    private List<ExtractedCandidate> blocks;
    private int chunks;

    @BeforeEach
    void setUp() {
        lenient().when(usage.tryConsume()).thenReturn(true);
        service = new DocumentAnalysisService(mock(ImportBatchRepository.class), mock(DocumentAnalysisRepository.class),
            List.of(new FreeformDocumentParser()), triager, synthesizer, critic, briefs, usage, mock(AuditService.class), new ObjectMapper());
        blocks = new FreeformDocumentParser().parse(DOC);
        chunks = DocumentAnalysisService.chunk(blocks).size();
        assertThat(chunks).as("the document must be several chunks for these tests to mean anything").isGreaterThanOrEqualTo(3);
    }

    @Test
    void VYB0940_AC1_aRunThatFailsOnALaterChunkIsContinuedNotRestarted() {
        MemorySteps steps = new MemorySteps();
        triager.failOnCall = 3;

        assertThatThrownBy(() -> service.run("spec.txt", blocks, true, steps))
            .isInstanceOf(AiProviderUnavailableException.class).hasMessageContaining("call 3");
        assertThat(steps.saved).as("the two chunks that were read are kept").containsOnlyKeys("triage:0", "triage:1");

        triager.failOnCall = -1;
        triager.calls = 0;
        DocumentAnalysisService.Run run = service.run("spec.txt", blocks, true, steps);

        assertThat(triager.calls).as("only the chunks not yet read are sent to the model").isEqualTo(chunks - 2);
        assertThat(run.chunksAnalysed()).as("but every chunk counts as read").isEqualTo(chunks);
        assertThat(run.aiCalls()).as("the calls made in this attempt, not the ones reused").isLessThan(chunks + 2 + 2);
    }

    @Test
    void VYB0940_AC2_aContinuedRunEndsWithExactlyWhatAnUninterruptedRunProduces() {
        DocumentAnalysisService.Run straight = service.run("spec.txt", blocks, true, new MemorySteps());

        MemorySteps steps = new MemorySteps();
        triager.calls = 0;
        triager.failOnCall = 2;
        assertThatThrownBy(() -> service.run("spec.txt", blocks, true, steps)).isInstanceOf(AiProviderUnavailableException.class);
        triager.failOnCall = -1;
        DocumentAnalysisService.Run continued = service.run("spec.txt", blocks, true, steps);

        assertThat(continued.findings()).isEqualTo(straight.findings());
        assertThat(continued.description()).isEqualTo(straight.description());
        assertThat(continued.themes()).isEqualTo(straight.themes());
        assertThat(continued.briefs()).isEqualTo(straight.briefs());
        assertThat(continued.chunksAnalysed()).isEqualTo(straight.chunksAnalysed());
        assertThat(continued.noiseBlocksDiscarded()).isEqualTo(straight.noiseBlocksDiscarded());
    }

    @Test
    void VYB0940_AC3_aFailureInTheCheckKeepsTheChunksAndTheDescriptionAndOnlyTheCheckIsMadeAgain() {
        MemorySteps steps = new MemorySteps();
        critic.failNext = true;
        assertThatThrownBy(() -> service.run("spec.txt", blocks, true, steps)).isInstanceOf(AiProviderUnavailableException.class);
        assertThat(steps.saved).containsKey("synthesis:0").doesNotContainKey("critique:0");
        int triageCalls = triager.calls;
        int synthesisCalls = synthesizer.calls;

        service.run("spec.txt", blocks, true, steps);

        assertThat(triager.calls).as("no chunk is read again").isEqualTo(triageCalls);
        assertThat(synthesizer.calls).as("the description is not written again").isEqualTo(synthesisCalls);
        assertThat(critic.calls).as("the check ran once failing and once succeeding").isEqualTo(2);
    }

    @Test
    void VYB0940_AC4_aFailureOnALaterBatchOfBriefsKeepsTheEarlierBatches() {
        MemorySteps steps = new MemorySteps();
        briefs.failOnCall = 2;
        assertThatThrownBy(() -> service.run("spec.txt", blocks, true, steps)).isInstanceOf(AiProviderUnavailableException.class);
        assertThat(steps.saved).containsKey("briefs:0").doesNotContainKey("briefs:6");

        briefs.failOnCall = -1;
        briefs.calls = 0;
        DocumentAnalysisService.Run run = service.run("spec.txt", blocks, true, steps);

        assertThat(briefs.calls).as("only the second batch is sent again").isEqualTo(1);
        assertThat(run.briefs()).as("and every finding has its brief").hasSize(run.findings().size());
    }

    @Test
    void VYB0940_AC5_aStepAlreadyDoneSpendsNoShareOfThePerRunBudget() {
        MemorySteps steps = new MemorySteps();
        service.run("spec.txt", blocks, true, steps);
        org.mockito.Mockito.clearInvocations(usage);

        service.run("spec.txt", blocks, true, steps);

        org.mockito.Mockito.verify(usage, org.mockito.Mockito.never()).tryConsume();
    }

    @Test
    void VYB0940_AC6_withNoStepsNothingIsRememberedAndEveryCallIsMadeAgain() {
        service.run("spec.txt", blocks, true);
        int first = triager.calls;

        service.run("spec.txt", blocks, true);

        assertThat(triager.calls).isEqualTo(first * 2);
    }

    @Test
    void VYB0940_AC7_aSavedStepThatCannotBeReadBackIsMadeAgainNeverGuessed() {
        // a saved reply that no longer fits what the pipeline expects
        MemorySteps steps = new MemorySteps();
        steps.saved.put("triage:0", "{\"findings\": \"not a list\"}");
        ExtractionSteps tolerant = new ExtractionSteps() {
            @Override public <T> Optional<T> load(String key, Class<T> type) {
                try {
                    return steps.load(key, type);
                } catch (AssertionError unreadable) {
                    return Optional.empty();
                }
            }

            @Override public <T> void save(String key, T value) {
                steps.save(key, value);
            }
        };

        DocumentAnalysisService.Run run = service.run("spec.txt", blocks, true, tolerant);

        assertThat(triager.calls).as("every chunk, including the first, was read").isEqualTo(chunks);
        assertThat(run.chunksAnalysed()).isEqualTo(chunks);
    }
}
