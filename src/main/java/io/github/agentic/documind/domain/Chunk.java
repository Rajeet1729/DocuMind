package io.github.agentic.documind.domain;

/**
 * A unit of text we embed, store, and retrieve, together with its {@link Provenance}.
 * One PDF page is split into one or more chunks; each chunk is independently
 * searchable and independently citable.
 *
 * <p>Pure domain value — no Spring or Spring AI types.
 *
 * @param text       the chunk's text content
 * @param provenance where this text came from
 */
public record Chunk(String text, Provenance provenance) {

    public Chunk {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (provenance == null) {
            throw new IllegalArgumentException("provenance must not be null");
        }
    }
}
