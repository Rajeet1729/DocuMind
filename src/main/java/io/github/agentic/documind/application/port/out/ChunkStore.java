package io.github.agentic.documind.application.port.out;

import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.RetrievedChunk;

import java.util.List;

/**
 * Outbound port: persist chunks and retrieve them by semantic similarity.
 *
 * <p>This is where the Spring AI {@code VectorStore} boundary sits. Its single
 * adapter is the one place the mapping between a {@link Chunk}'s provenance and the
 * vector store's metadata keys is defined — and it must be applied identically on
 * {@link #save} (write) and {@link #search} (read) so citations round-trip exactly.
 *
 * <p>Embedding happens inside the adapter (the vector store embeds on write and on
 * query); the domain never sees an embedding model.
 */
public interface ChunkStore {

    /**
     * Embed and store the given chunks.
     */
    void save(List<Chunk> chunks);

    /**
     * Find the {@code topK} chunks most similar to the query text.
     *
     * @param query the natural-language query to embed and match against
     * @param topK  maximum number of chunks to return
     * @return retrieved chunks with scores, most relevant first
     */
    List<RetrievedChunk> search(String query, int topK);
}
