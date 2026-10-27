package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestCommand;
import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestResult;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.DocumentChunker;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    private final DocumentChunker chunker = mock(DocumentChunker.class);
    private final ChunkStore chunkStore = mock(ChunkStore.class);
    private final IngestionService service = new IngestionService(chunker, chunkStore);

    @Test
    void mintsIdChunksWithItAndStoresTheChunks() {
        byte[] content = "pdf-bytes".getBytes();
        List<Chunk> chunks = List.of(
                new Chunk("one", new Provenance("will-be-set", "doc.pdf", 1, 0)),
                new Chunk("two", new Provenance("will-be-set", "doc.pdf", 1, 1)));
        when(chunker.chunk(any(), eq("doc.pdf"), eq(content))).thenReturn(chunks);

        IngestResult result = service.ingest(new IngestCommand("doc.pdf", content));

        // The service mints the identity and reports the chunk count it stored.
        assertThat(result.documentId()).isNotBlank();
        assertThat(result.chunkCount()).isEqualTo(2);

        // The id handed to the chunker is exactly the one returned to the caller.
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunker).chunk(idCaptor.capture(), eq("doc.pdf"), eq(content));
        assertThat(idCaptor.getValue()).isEqualTo(result.documentId());

        // The produced chunks are persisted as-is.
        verify(chunkStore).save(chunks);
    }

    @Test
    void mintsADistinctIdPerIngest() {
        byte[] content = "bytes".getBytes();
        when(chunker.chunk(any(), any(), any())).thenReturn(List.of());

        String first = service.ingest(new IngestCommand("a.pdf", content)).documentId();
        String second = service.ingest(new IngestCommand("a.pdf", content)).documentId();

        assertThat(first).isNotEqualTo(second);
    }
}
