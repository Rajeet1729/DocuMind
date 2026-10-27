package io.github.agentic.documind.adapter.in.web;

import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestResult;

/**
 * Response body for {@code POST /api/documents}. Maps the use-case result into the
 * web layer's own shape.
 *
 * @param documentId stable id assigned to the ingested document
 * @param chunkCount number of chunks produced and stored
 */
public record IngestResponse(String documentId, int chunkCount) {

    public static IngestResponse from(IngestResult result) {
        return new IngestResponse(result.documentId(), result.chunkCount());
    }
}
