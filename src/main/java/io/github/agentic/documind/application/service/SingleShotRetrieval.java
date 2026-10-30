package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * One {@link ChunkStore#search} with the question — the pre-Slice-4 retrieval path,
 * unchanged. Active unless {@code documind.retrieval.agentic.enabled=true}, so the default
 * build behaves exactly as Slice 3 and is the single-shot arm of the agentic A/B.
 */
@Service
@ConditionalOnProperty(name = "documind.retrieval.agentic.enabled", havingValue = "false", matchIfMissing = true)
public class SingleShotRetrieval implements RetrievalStrategy {

    private final ChunkStore chunkStore;

    public SingleShotRetrieval(ChunkStore chunkStore) {
        this.chunkStore = chunkStore;
    }

    @Override
    public Result retrieve(String question, int topK) {
        List<RetrievedChunk> retrieved = chunkStore.search(question, topK);
        return new Result(retrieved, Trace.singleShot(question, retrieved.size()));
    }
}
