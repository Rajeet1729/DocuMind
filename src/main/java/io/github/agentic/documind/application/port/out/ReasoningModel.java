package io.github.agentic.documind.application.port.out;

import java.util.List;

/**
 * Outbound port: the LLM reasoning calls the agentic retrieval loop makes <em>inside</em>
 * the retrieval path — judging whether retrieved context is sufficient, and reformulating
 * the search query when it is not.
 *
 * <p>This is the narrow port the Slice-1 decision deferred (ADR 0001 injected the
 * {@code ChatClient} straight into the answering service). It earns its place here: the
 * loop is a second, structured LLM dependency of the application layer, and behind a
 * port its control flow is unit-testable with scripted verdicts and no model (ADR 0002).
 * Answer generation is deliberately <b>not</b> moved behind it.
 *
 * <p>The verdict is modelled as a nested record so the port owns its own response shape.
 */
public interface ReasoningModel {

    /**
     * Decide whether the context holds the information needed to answer the question.
     *
     * @param question the user's original question
     * @param context  the numbered context block, exactly as the answer generator would see it
     */
    SufficiencyVerdict judgeSufficiency(String question, String context);

    /**
     * Write a new search query aimed at the missing information.
     *
     * @param question     the user's original question
     * @param missingInfo  what the sufficiency verdict said is missing (may be empty)
     * @param triedQueries queries already run, so the new one does not repeat them
     * @return the new query, or an empty string if none could be produced
     */
    String reformulate(String question, String missingInfo, List<String> triedQueries);

    enum Sufficiency {
        SUFFICIENT, INSUFFICIENT, UNPARSEABLE
    }

    /**
     * @param sufficiency the gate's decision; {@code UNPARSEABLE} if the reply could not be read
     * @param missingInfo what the context lacks; empty when sufficient or unknown
     */
    record SufficiencyVerdict(Sufficiency sufficiency, String missingInfo) {
        public SufficiencyVerdict {
            if (sufficiency == null) {
                throw new IllegalArgumentException("sufficiency must not be null");
            }
            missingInfo = missingInfo == null ? "" : missingInfo.strip();
        }
    }
}
