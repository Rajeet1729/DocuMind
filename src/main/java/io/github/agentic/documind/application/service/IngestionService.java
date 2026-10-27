package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.in.IngestDocumentUseCase;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.DocumentChunker;
import io.github.agentic.documind.domain.Chunk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Drives the ingest use case: mint an identity, chunk, store.
 *
 * <p>Identity is minted here (once, by the service) and handed to the chunker, which is
 * the only component that knows page numbers and chunk ordering and so assembles the
 * full provenance. The store then embeds and persists.
 */
@Service
public class IngestionService implements IngestDocumentUseCase {

    private final DocumentChunker chunker;
    private final ChunkStore chunkStore;

    public IngestionService(DocumentChunker chunker, ChunkStore chunkStore) {
        this.chunker = chunker;
        this.chunkStore = chunkStore;
    }

    @Override
    public IngestResult ingest(IngestCommand command) {
        String documentId = UUID.randomUUID().toString();
        List<Chunk> chunks = chunker.chunk(documentId, command.filename(), command.content());
        chunkStore.save(chunks);
        return new IngestResult(documentId, chunks.size());
    }
}
