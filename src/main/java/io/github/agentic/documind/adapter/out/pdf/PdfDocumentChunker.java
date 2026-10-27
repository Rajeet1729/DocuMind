package io.github.agentic.documind.adapter.out.pdf;

import io.github.agentic.documind.application.port.out.DocumentChunker;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link DocumentChunker} for PDFs: a page-aware read ({@link PagePdfDocumentReader},
 * one Spring AI {@code Document} per page) followed by a token split
 * ({@link TokenTextSplitter}). This is the only place that knows page numbers and
 * chunk ordering, so it assembles the full {@link Provenance} on every chunk.
 *
 * <p>Reading per page keeps each chunk anchored to a single, citable page: the splitter
 * copies the source page's metadata onto each chunk it produces, and a running counter
 * assigns the 0-based {@code chunkIndex} in reading order across the whole document.
 *
 * <p><b>On overlap:</b> {@code TokenTextSplitter} splits strictly by token budget and
 * has no overlap setting, so there is no overlap knob to expose here. {@code chunkSize}
 * and {@code minChunkSizeChars} are its real tunables; overlap, if we ever want it,
 * would need a different splitter or a post-processing pass.
 */
@Component
public class PdfDocumentChunker implements DocumentChunker {

    // PagePdfDocumentReader emits 1-based page numbers under this metadata key, which
    // lines up with Provenance's 1-based pageNumber directly.
    private static final String PAGE_NUMBER_KEY = PagePdfDocumentReader.METADATA_START_PAGE_NUMBER;

    private final TokenTextSplitter splitter;

    public PdfDocumentChunker(
            @Value("${documind.chunking.chunk-size:800}") int chunkSize,
            @Value("${documind.chunking.min-chunk-size-chars:350}") int minChunkSizeChars) {
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .build();
    }

    @Override
    public List<Chunk> chunk(String documentId, String filename, byte[] content) {
        List<Document> pages = new PagePdfDocumentReader(asResource(content, filename),
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1) // one Document per page => clean page provenance
                        .build())
                .get();

        // split(pages) keeps reading order and copies each page's metadata onto its chunks.
        List<Document> splitDocuments = splitter.split(pages);

        List<Chunk> chunks = new ArrayList<>(splitDocuments.size());
        int chunkIndex = 0;
        for (Document document : splitDocuments) {
            Provenance provenance = new Provenance(documentId, filename, pageNumberOf(document), chunkIndex++);
            chunks.add(new Chunk(document.getText(), provenance));
        }
        return chunks;
    }

    // ByteArrayResource has no filename; PagePdfDocumentReader reads getFilename(), so
    // give it ours to keep its internal metadata non-null (we stamp provenance from the
    // filename argument regardless).
    private static Resource asResource(byte[] content, String filename) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static int pageNumberOf(Document document) {
        Object value = document.getMetadata().get(PAGE_NUMBER_KEY);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException(
                "page Document is missing numeric '" + PAGE_NUMBER_KEY + "' metadata, got: " + value);
    }
}
