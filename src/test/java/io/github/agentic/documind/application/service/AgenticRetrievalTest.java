package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.ReasoningModel;
import io.github.agentic.documind.application.service.RetrievalStrategy.Result;
import io.github.agentic.documind.application.service.RetrievalStrategy.StopReason;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.agentic.documind.application.port.out.ReasoningModel.Sufficiency.INSUFFICIENT;
import static io.github.agentic.documind.application.port.out.ReasoningModel.Sufficiency.SUFFICIENT;
import static io.github.agentic.documind.application.port.out.ReasoningModel.Sufficiency.UNPARSEABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Control flow of the agentic loop with a scripted {@link ReasoningModel} and a
 * map-backed {@link ChunkStore} — no model, no Elasticsearch, fully deterministic.
 */
class AgenticRetrievalTest {

    private static RetrievedChunk chunk(String id, double score) {
        return new RetrievedChunk(new Chunk("text " + id, new Provenance("doc", "a.pdf", 1, Integer.parseInt(id))), score);
    }

    private static List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(c -> String.valueOf(c.chunk().provenance().chunkIndex())).toList();
    }

    /** Returns a canned result per query and records every query it was asked. */
    private static final class FakeStore implements ChunkStore {
        final Map<String, List<RetrievedChunk>> results = new HashMap<>();
        final List<String> searched = new ArrayList<>();

        @Override
        public void save(List<Chunk> chunks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RetrievedChunk> search(String query, int topK) {
            searched.add(query);
            List<RetrievedChunk> hits = results.getOrDefault(query, List.of());
            return hits.subList(0, Math.min(topK, hits.size()));
        }
    }

    /** Plays back scripted verdicts and reformulations, recording what it was given. */
    private static final class ScriptedReasoning implements ReasoningModel {
        final Deque<SufficiencyVerdict> verdicts = new ArrayDeque<>();
        final Deque<String> reformulations = new ArrayDeque<>();
        final List<String> gateQuestions = new ArrayList<>();
        final List<String> gateContexts = new ArrayList<>();
        final List<List<String>> triedQueries = new ArrayList<>();

        ScriptedReasoning verdict(Sufficiency s, String missing) {
            verdicts.add(new SufficiencyVerdict(s, missing));
            return this;
        }

        ScriptedReasoning reformulation(String q) {
            reformulations.add(q);
            return this;
        }

        @Override
        public SufficiencyVerdict judgeSufficiency(String question, String context) {
            gateQuestions.add(question);
            gateContexts.add(context);
            return verdicts.removeFirst();
        }

        @Override
        public String reformulate(String question, String missingInfo, List<String> tried) {
            triedQueries.add(tried);
            return reformulations.removeFirst();
        }
    }

    private final FakeStore store = new FakeStore();
    private final ScriptedReasoning reasoning = new ScriptedReasoning();

    @Test
    void sufficientOnFirstPassReturnsTheFirstSearchUntouched() {
        List<RetrievedChunk> first = List.of(chunk("1", 0.9), chunk("2", 0.8));
        store.results.put("q", first);
        reasoning.verdict(SUFFICIENT, "");

        Result result = new AgenticRetrieval(store, reasoning, 2).retrieve("q", 2);

        assertThat(result.context()).isEqualTo(first); // same chunks AND same scores as single-shot
        assertThat(store.searched).containsExactly("q");
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.SUFFICIENT);
        assertThat(result.trace().reasoningCalls()).isEqualTo(1);
        assertThat(result.trace().retried()).isFalse();
    }

    @Test
    void gateSeesTheOriginalQuestionAndTheGeneratorsNumberedContext() {
        store.results.put("q", List.of(chunk("1", 0.9), chunk("2", 0.8)));
        reasoning.verdict(SUFFICIENT, "");

        new AgenticRetrieval(store, reasoning, 2).retrieve("q", 2);

        assertThat(reasoning.gateQuestions).containsExactly("q");
        assertThat(reasoning.gateContexts).containsExactly("[1] text 1\n\n[2] text 2");
    }

    @Test
    void insufficientRetriesOnceThenFusesAndCapsToTopKWithTiesGoingToTheOriginalQuery() {
        store.results.put("q", List.of(chunk("1", 0.9), chunk("2", 0.8)));
        store.results.put("q2", List.of(chunk("3", 0.7), chunk("4", 0.6)));
        reasoning.verdict(INSUFFICIENT, "the dose").reformulation("q2");

        Result result = new AgenticRetrieval(store, reasoning, 2).retrieve("q", 2);

        // No overlap: fused order is 1 (q rank1), 3 (q2 rank1), 2, 4 — capped to 2.
        // Chunk 2, which single-shot would have kept, is evicted by the cap.
        assertThat(ids(result.context())).containsExactly("1", "3");
        assertThat(store.searched).containsExactly("q", "q2");
        assertThat(result.trace().queries()).containsExactly("q", "q2");
        assertThat(result.trace().candidatesExamined()).isEqualTo(4);
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.MAX_ITERATIONS);
        // No gate after the final pass: one gate + one reformulation.
        assertThat(result.trace().reasoningCalls()).isEqualTo(2);
        assertThat(reasoning.triedQueries).containsExactly(List.of("q"));
    }

    @Test
    void aChunkBothQueriesFoundOutranksEitherQuerysSoloHits() {
        store.results.put("q", List.of(chunk("1", 0.9), chunk("2", 0.8)));
        store.results.put("q2", List.of(chunk("3", 0.7), chunk("2", 0.6)));
        reasoning.verdict(INSUFFICIENT, "").reformulation("q2");

        Result result = new AgenticRetrieval(store, reasoning, 2).retrieve("q", 2);

        assertThat(ids(result.context())).containsExactly("2", "1");
        assertThat(result.trace().candidatesExamined()).isEqualTo(3);
    }

    @Test
    void aRetryThatFindsNothingNewStopsWithTheContextUnchanged() {
        List<RetrievedChunk> first = List.of(chunk("1", 0.9), chunk("2", 0.8));
        store.results.put("q", first);
        store.results.put("q2", List.of(chunk("2", 0.7), chunk("1", 0.6)));
        reasoning.verdict(INSUFFICIENT, "").verdict(INSUFFICIENT, "")
                .reformulation("q2").reformulation("q3");

        Result result = new AgenticRetrieval(store, reasoning, 3).retrieve("q", 2);

        assertThat(result.context()).isEqualTo(first);
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.NO_NEW_CHUNKS);
        assertThat(store.searched).containsExactly("q", "q2"); // q3 never requested
        assertThat(result.trace().reasoningCalls()).isEqualTo(2);
    }

    @Test
    void theIterationCapBoundsSearchesAndThereIsNoGateAfterTheLastPass() {
        store.results.put("q", List.of(chunk("1", 0.9)));
        store.results.put("q2", List.of(chunk("2", 0.9)));
        store.results.put("q3", List.of(chunk("3", 0.9)));
        store.results.put("q4", List.of(chunk("4", 0.9)));
        reasoning.verdict(INSUFFICIENT, "").verdict(INSUFFICIENT, "").verdict(INSUFFICIENT, "")
                .reformulation("q2").reformulation("q3").reformulation("q4");

        Result result = new AgenticRetrieval(store, reasoning, 3).retrieve("q", 1);

        assertThat(store.searched).containsExactly("q", "q2", "q3");
        assertThat(result.trace().verdicts()).hasSize(2);
        assertThat(result.trace().reformulateCalls()).isEqualTo(2);
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.MAX_ITERATIONS);
        assertThat(reasoning.triedQueries).containsExactly(List.of("q"), List.of("q", "q2"));
        // At k=1 every solo hit ties at rank 1 and ties go to the original query, so a
        // non-overlapping retry can never displace the original top hit: the cap admits
        // floor(k/2) reformulation chunks, i.e. none at k=1.
        assertThat(ids(result.context())).containsExactly("1");
    }

    @Test
    void anUnparseableGateStopsWithTheContextInHandAndNeverReformulates() {
        List<RetrievedChunk> first = List.of(chunk("1", 0.9));
        store.results.put("q", first);
        reasoning.verdict(UNPARSEABLE, "");

        Result result = new AgenticRetrieval(store, reasoning, 2).retrieve("q", 1);

        assertThat(result.context()).isEqualTo(first);
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.GATE_UNPARSEABLE);
        assertThat(result.trace().reformulateCalls()).isZero();
    }

    @Test
    void aBlankReformulationStopsWithoutASecondSearch() {
        store.results.put("q", List.of(chunk("1", 0.9)));
        reasoning.verdict(INSUFFICIENT, "x").reformulation("  ");

        Result result = new AgenticRetrieval(store, reasoning, 2).retrieve("q", 1);

        assertThat(store.searched).containsExactly("q");
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.BLANK_REFORMULATION);
        assertThat(result.trace().reasoningCalls()).isEqualTo(2);
    }

    @Test
    void emptyRetrievalSpendsNoReasoningCalls() {
        Result result = new AgenticRetrieval(store, reasoning, 2).retrieve("q", 2);

        assertThat(result.context()).isEmpty();
        assertThat(result.trace().stopReason()).isEqualTo(StopReason.NO_RESULTS);
        assertThat(result.trace().reasoningCalls()).isZero();
    }

    @Test
    void oneIterationIsSingleShot() {
        List<RetrievedChunk> first = List.of(chunk("1", 0.9));
        store.results.put("q", first);

        Result result = new AgenticRetrieval(store, reasoning, 1).retrieve("q", 1);

        assertThat(result.context()).isEqualTo(first);
        assertThat(result.trace().reasoningCalls()).isZero();
    }

    @Test
    void rejectsANonPositiveCap() {
        assertThatThrownBy(() -> new AgenticRetrieval(store, reasoning, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
