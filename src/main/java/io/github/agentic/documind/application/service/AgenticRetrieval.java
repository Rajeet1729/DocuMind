package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.ReasoningModel;
import io.github.agentic.documind.application.port.out.ReasoningModel.Sufficiency;
import io.github.agentic.documind.application.port.out.ReasoningModel.SufficiencyVerdict;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-correcting retrieval: retrieve → judge sufficiency → if insufficient, reformulate
 * and retry — bounded by {@code documind.retrieval.agentic.max-iterations} retrieval passes.
 *
 * <p>Decisions (Slice 4 design):
 * <ul>
 *   <li><b>Trigger = an LLM sufficiency gate, no score threshold.</b> Retrieval scores are
 *       ordering-only (RRF is unitless), so an absolute cutoff would be uncalibrated. The
 *       gate judges the <em>original question</em> against the numbered context exactly as
 *       the generator would see it. No gate runs after the final pass — its verdict could
 *       not change anything — so a fired loop costs two reasoning calls at the default cap.</li>
 *   <li><b>Accumulate, then cap.</b> Each pass's hits are fused with the earlier passes by
 *       Reciprocal Rank Fusion over the per-pass lists, and the result is capped to
 *       {@code topK} — the generator sees the same budget as single-shot. Because of the
 *       cap, the final context is <b>not</b> a superset of the first pass: a chunk the first
 *       pass kept can be evicted. That is measured per question, not claimed away.</li>
 *   <li><b>Ties go to the earlier pass.</b> A chunk both queries found outranks either
 *       query's solo hits; among equal fused scores the original query's chunk wins. So a
 *       false-positive gate cannot demote the original top hit — the cap only swaps the
 *       original's tail for the reformulation's best.</li>
 *   <li><b>Stop when no progress.</b> A retry that surfaces no unseen chunk ends the loop
 *       with the context unchanged, rather than spending another pass.</li>
 *   <li><b>If the loop never retries, the context is the first search's result as-is</b>
 *       (same chunks, same scores), so agentic and single-shot differ only on questions
 *       where the gate fired.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "documind.retrieval.agentic.enabled", havingValue = "true")
public class AgenticRetrieval implements RetrievalStrategy {

    /** Standard RRF rank constant — the same value the hybrid adapter fuses with. */
    static final int RRF_K = 60;

    private final ChunkStore chunkStore;
    private final ReasoningModel reasoningModel;
    private final int maxIterations;

    public AgenticRetrieval(ChunkStore chunkStore, ReasoningModel reasoningModel,
                            @Value("${documind.retrieval.agentic.max-iterations:2}") int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("max-iterations must be >= 1, got " + maxIterations);
        }
        this.chunkStore = chunkStore;
        this.reasoningModel = reasoningModel;
        this.maxIterations = maxIterations;
    }

    @Override
    public Result retrieve(String question, int topK) {
        List<String> queries = new ArrayList<>();
        List<SufficiencyVerdict> verdicts = new ArrayList<>();
        List<List<RetrievedChunk>> passes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<RetrievedChunk> context = List.of();
        int reformulateCalls = 0;
        StopReason stopReason = StopReason.MAX_ITERATIONS;

        String query = question;
        for (int pass = 1; pass <= maxIterations; pass++) {
            List<RetrievedChunk> hits = chunkStore.search(query, topK);
            queries.add(query);
            boolean progressed = false;
            for (RetrievedChunk hit : hits) {
                progressed |= seen.add(key(hit.chunk()));
            }
            if (pass > 1 && !progressed) {
                stopReason = StopReason.NO_NEW_CHUNKS;
                break;
            }
            passes.add(hits);
            context = fuse(passes, topK);
            if (context.isEmpty()) {
                stopReason = StopReason.NO_RESULTS;
                break;
            }
            if (pass == maxIterations) {
                break; // stopReason stays MAX_ITERATIONS; a final gate could not change anything
            }

            SufficiencyVerdict verdict = reasoningModel.judgeSufficiency(
                    question, QuestionAnsweringService.buildContext(context));
            verdicts.add(verdict);
            if (verdict.sufficiency() == Sufficiency.SUFFICIENT) {
                stopReason = StopReason.SUFFICIENT;
                break;
            }
            if (verdict.sufficiency() == Sufficiency.UNPARSEABLE) {
                stopReason = StopReason.GATE_UNPARSEABLE;
                break;
            }

            String next = reasoningModel.reformulate(question, verdict.missingInfo(), List.copyOf(queries));
            reformulateCalls++;
            if (next == null || next.isBlank()) {
                stopReason = StopReason.BLANK_REFORMULATION;
                break;
            }
            query = next.strip();
        }

        return new Result(context, new Trace(queries, verdicts, reformulateCalls, seen.size(), stopReason));
    }

    /**
     * Fuse the per-pass ranked lists by Reciprocal Rank Fusion and keep the top {@code topK}.
     * A single pass is returned untouched. Insertion order is pass-then-rank and the sort is
     * stable, so equal fused scores resolve to the earlier pass (then the better rank).
     */
    static List<RetrievedChunk> fuse(List<List<RetrievedChunk>> passes, int topK) {
        if (passes.size() == 1) {
            return passes.get(0);
        }
        Map<String, Double> fusedScore = new LinkedHashMap<>();
        Map<String, Chunk> representative = new LinkedHashMap<>();
        for (List<RetrievedChunk> pass : passes) {
            for (int rank = 0; rank < pass.size(); rank++) {
                Chunk chunk = pass.get(rank).chunk();
                fusedScore.merge(key(chunk), 1.0 / (RRF_K + rank + 1), Double::sum); // rank+1 → 1-based
                representative.putIfAbsent(key(chunk), chunk);
            }
        }
        return fusedScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new RetrievedChunk(representative.get(e.getKey()), e.getValue()))
                .toList();
    }

    /** Stable chunk identity — the same {@code documentId#chunkIndex} the store keys on. */
    private static String key(Chunk chunk) {
        Provenance p = chunk.provenance();
        return p.documentId() + "#" + p.chunkIndex();
    }
}
