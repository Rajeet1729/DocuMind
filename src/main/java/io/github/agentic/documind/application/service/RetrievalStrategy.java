package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.out.ReasoningModel.SufficiencyVerdict;
import io.github.agentic.documind.domain.RetrievedChunk;

import java.util.List;

/**
 * How {@link QuestionAnsweringService} assembles the context it grounds an answer on.
 *
 * <p>Two implementations, chosen by {@code documind.retrieval.agentic.enabled}:
 * {@link SingleShotRetrieval} (one {@code ChunkStore.search}, the default) and
 * {@link AgenticRetrieval} (a bounded retrieve → judge → reformulate loop). Both use the
 * same {@code ChunkStore} as their retrieval primitive, and both return at most
 * {@code topK} chunks — so the generator always sees the same context budget and the two
 * arms differ only in <em>which</em> chunks they pick.
 *
 * <p>The {@link Trace} records what the strategy did (queries run, gate verdicts, chunks
 * examined, why it stopped), so the loop's behaviour and cost are observable rather than
 * inferred.
 */
public interface RetrievalStrategy {

    Result retrieve(String question, int topK);

    /**
     * @param context the chunks to ground on, most relevant first, at most {@code topK}
     * @param trace   what the strategy did to produce them
     */
    record Result(List<RetrievedChunk> context, Trace trace) {
        public Result {
            context = List.copyOf(context);
            if (trace == null) {
                throw new IllegalArgumentException("trace must not be null");
            }
        }
    }

    /**
     * @param queries            every query sent to the store, in order (the first is the question)
     * @param verdicts           every sufficiency verdict, in order
     * @param reformulateCalls   reformulation calls made (one may yield no usable query)
     * @param candidatesExamined distinct chunks retrieved across all passes, before the cap
     * @param stopReason         why the strategy stopped
     */
    record Trace(List<String> queries, List<SufficiencyVerdict> verdicts, int reformulateCalls,
                 int candidatesExamined, StopReason stopReason) {
        public Trace {
            queries = List.copyOf(queries);
            verdicts = List.copyOf(verdicts);
            if (stopReason == null) {
                throw new IllegalArgumentException("stopReason must not be null");
            }
        }

        static Trace singleShot(String question, int retrieved) {
            return new Trace(List.of(question), List.of(), 0, retrieved, StopReason.SINGLE_SHOT);
        }

        /** LLM calls the strategy made inside retrieval (gate + reformulation). */
        public int reasoningCalls() {
            return verdicts.size() + reformulateCalls;
        }

        /** True if a reformulated query was actually run against the store. */
        public boolean retried() {
            return queries.size() > 1;
        }
    }

    enum StopReason {
        /** Single-shot strategy: one search, no loop. */
        SINGLE_SHOT,
        /** The store returned nothing; there is nothing to judge or reformulate from. */
        NO_RESULTS,
        /** The gate judged the context sufficient. */
        SUFFICIENT,
        /** The gate's reply could not be read; fall back to the context in hand. */
        GATE_UNPARSEABLE,
        /** Reformulation produced no usable query. */
        BLANK_REFORMULATION,
        /** A retry surfaced only chunks already seen — no progress, so stop spending. */
        NO_NEW_CHUNKS,
        /** The iteration cap was reached. */
        MAX_ITERATIONS
    }
}
