package io.github.agentic.documind.adapter.in.web;

import io.github.agentic.documind.application.port.in.IngestDocumentUseCase;
import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestCommand;
import io.github.agentic.documind.application.port.in.IngestDocumentUseCase.IngestResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    IngestDocumentUseCase ingestDocument;

    @Test
    void multipartUploadDrivesTheUseCaseAndReturns201WithResult() throws Exception {
        when(ingestDocument.ingest(any())).thenReturn(new IngestResult("doc-123", 7));
        MockMultipartFile file = new MockMultipartFile(
                "file", "paper.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf-bytes".getBytes());

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value("doc-123"))
                .andExpect(jsonPath("$.chunkCount").value(7));

        // The controller passes the upload straight through to the use case.
        ArgumentCaptor<IngestCommand> command = ArgumentCaptor.forClass(IngestCommand.class);
        verify(ingestDocument).ingest(command.capture());
        assertThat(command.getValue().filename()).isEqualTo("paper.pdf");
        assertThat(command.getValue().content()).isEqualTo("pdf-bytes".getBytes());
    }

    @Test
    void emptyFileIsRejectedWith400AndNeverReachesTheUseCase() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);

        mockMvc.perform(multipart("/api/documents").file(empty))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ingestDocument);
    }
}
