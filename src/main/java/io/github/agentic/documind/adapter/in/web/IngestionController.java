package io.github.agentic.documind.adapter.in.web;

import io.github.agentic.documind.application.port.in.IngestDocumentUseCase;
import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestCommand;
import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Web adapter driving {@link IngestDocumentUseCase}. Translates a multipart upload into
 * a use-case call and the result into JSON — no ingestion logic lives here. Depends on
 * the inbound port, never on the service implementation.
 */
@RestController
public class IngestionController {

    private final IngestDocumentUseCase ingestDocument;

    public IngestionController(IngestDocumentUseCase ingestDocument) {
        this.ingestDocument = ingestDocument;
    }

    @PostMapping(path = "/api/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResponse ingest(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must have a filename");
        }
        IngestResult result = ingestDocument.ingest(new IngestCommand(filename, file.getBytes()));
        return IngestResponse.from(result);
    }
}
