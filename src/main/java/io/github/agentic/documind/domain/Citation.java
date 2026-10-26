package io.github.agentic.documind.domain;

/**
 * A reference back to the source material an {@link Answer} was grounded in.
 *
 * <p>In slice 1, a citation is the provenance of a chunk that was placed into the
 * model's context — not something parsed out of the model's free-text reply.
 * "Which sources the model actually used" is a later slice.
 *
 * <p>Pure domain value — no Spring or Spring AI types.
 *
 * @param filename   source document the cited text came from
 * @param pageNumber 1-based page within that document
 * @param snippet    short excerpt of the cited chunk, for display
 */
public record Citation(String filename, int pageNumber, String snippet) {

    public Citation {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber is 1-based, got " + pageNumber);
        }
        if (snippet == null) {
            throw new IllegalArgumentException("snippet must not be null");
        }
    }
}
