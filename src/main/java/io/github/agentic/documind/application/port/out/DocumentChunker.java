package io.github.agentic.documind.application.port.out;

import io.github.agentic.documind.domain.Chunk;

import java.util.List;

/**
 * Outbound port: turn a raw document into page-aware, provenance-stamped chunks.
 *
 * <p>This is the volatile boundary where a specific parsing + splitting strategy
 * lives (page-aware PDF read then token split, in slice 1). The caller supplies the
 * {@code documentId} so that identity is minted once, by the service, while this
 * port — the only place that knows page numbers and chunk ordering — assembles the
 * full {@link io.github.agentic.documind.domain.Provenance} on each chunk.
 */
public interface DocumentChunker {

    /**
     * @param documentId stable id to stamp onto every chunk's provenance
     * @param filename   original filename, carried into provenance for citations
     * @param content    raw bytes of the document (a PDF in slice 1)
     * @return ordered chunks, each carrying full provenance
     */
    List<Chunk> chunk(String documentId, String filename, byte[] content);
}
