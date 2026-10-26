package io.github.agentic.documind.domain;

/**
 * Where a {@link Chunk} came from. This is the backbone of citations: every chunk
 * we embed and store carries its provenance, and an answer cites the provenance of
 * the chunks it was grounded in.
 *
 * <p>Pure domain value — no Spring or Spring AI types. Page numbers are 1-based to
 * match how a human reads a PDF ("see page 3").
 *
 * @param documentId stable id of the ingested source document
 * @param filename   original filename, shown to the user in a citation
 * @param pageNumber 1-based page the chunk was read from
 * @param chunkIndex 0-based position of this chunk within the document
 */
public record Provenance(String documentId, String filename, int pageNumber, int chunkIndex) {

    public Provenance {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber is 1-based, got " + pageNumber);
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0, got " + chunkIndex);
        }
    }
}
