package io.github.agentic.documind.application.port.in;

/**
 * Inbound port: ingest a single document into the knowledge base.
 *
 * <p>Driven in slice 1 by a REST controller; the same use case is the seam a future
 * MCP front door (slice 6) would drive, which is why the driving side is a port and
 * not just a controller method.
 *
 * <p>The command and result are modelled as nested records so the domain model stays
 * limited to its value types and the port owns its own request/response shape.
 */
public interface IngestDocumentUseCase {

    IngestResult ingest(IngestCommand command);

    /**
     * @param filename original filename of the uploaded document
     * @param content  raw bytes of the document (a PDF in slice 1)
     */
    record IngestCommand(String filename, byte[] content) {
        public IngestCommand {
            if (filename == null || filename.isBlank()) {
                throw new IllegalArgumentException("filename must not be blank");
            }
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }
    }

    /**
     * @param documentId stable id assigned to the ingested document
     * @param chunkCount number of chunks produced and stored
     */
    record IngestResult(String documentId, int chunkCount) {
    }
}
