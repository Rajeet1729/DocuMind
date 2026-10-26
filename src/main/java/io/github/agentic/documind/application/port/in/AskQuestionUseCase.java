package io.github.agentic.documind.application.port.in;

import io.github.agentic.documind.domain.Answer;

/**
 * Inbound port: answer a natural-language question grounded in ingested documents.
 *
 * <p>Returns a domain {@link Answer} carrying the generated text and the citations
 * for the chunks that grounded it. Retrieval parameters such as top-K are a concern
 * of the implementing service's configuration, not of this port.
 */
public interface AskQuestionUseCase {

    Answer ask(String question);
}
