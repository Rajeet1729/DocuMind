package io.github.agentic.documind.domain;

/**
 * A {@link Chunk} returned by a similarity search, paired with its relevance score.
 *
 * <p>Pure domain value — no Spring or Spring AI types. The score's exact scale is
 * defined by the store's similarity function (cosine, for Elasticsearch in slice 1);
 * the domain only relies on "higher means more relevant".
 *
 * @param chunk the retrieved chunk
 * @param score similarity score; higher is more relevant
 */
public record RetrievedChunk(Chunk chunk, double score) {

    public RetrievedChunk {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }
    }
}
