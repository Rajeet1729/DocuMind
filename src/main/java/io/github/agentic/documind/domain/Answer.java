package io.github.agentic.documind.domain;

import java.util.List;

/**
 * The result of asking a question: the generated text plus the citations for the
 * chunks that grounded it.
 *
 * <p>Pure domain value — no Spring or Spring AI types. The citations list is copied
 * defensively so an {@code Answer} is genuinely immutable once built.
 *
 * @param text      the generated answer
 * @param citations provenance of the chunks placed into the model's context
 */
public record Answer(String text, List<Citation> citations) {

    public Answer {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        // List.copyOf also rejects a null list or null elements.
        citations = List.copyOf(citations);
    }
}
