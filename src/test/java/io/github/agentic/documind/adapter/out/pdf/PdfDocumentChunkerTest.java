package io.github.agentic.documind.adapter.out.pdf;

import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Provenance;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link PdfDocumentChunker}. Builds a real two-page PDF in memory (no
 * fixture files, no Spring context) with a distinctive marker word per page, then
 * asserts the page-aware read + token split attributes every chunk to the correct page
 * and stamps contiguous, ordered provenance. A small chunk size forces each page to
 * split into several chunks, exercising the chunk-index counter.
 */
class PdfDocumentChunkerTest {

    private static final String PAGE_ONE_MARKER = "Francium";
    private static final String PAGE_TWO_MARKER = "Germanium";

    // Small token budget so each (long) page yields several chunks.
    private final PdfDocumentChunker chunker = new PdfDocumentChunker(24, 5);

    @Test
    void readsPagesSplitsIntoChunksAndStampsProvenance() throws IOException {
        byte[] pdf = twoPagePdf();

        List<Chunk> chunks = chunker.chunk("doc-1", "elements.pdf", pdf);

        assertThat(chunks).isNotEmpty();

        // Every chunk carries the caller's identity and the 1-based page it came from.
        assertThat(chunks).allSatisfy(chunk -> {
            Provenance p = chunk.provenance();
            assertThat(p.documentId()).isEqualTo("doc-1");
            assertThat(p.filename()).isEqualTo("elements.pdf");
            assertThat(p.pageNumber()).isBetween(1, 2);
        });

        // Both pages are represented.
        assertThat(chunks).extracting(c -> c.provenance().pageNumber())
                .contains(1, 2);

        // chunkIndex is 0-based, contiguous, and in reading order across the document.
        assertThat(chunks).extracting(c -> c.provenance().chunkIndex())
                .containsExactlyElementsOf(indices(chunks.size()));

        // Page attribution is exact: page-1 text stays on page 1, page-2 text on page 2.
        // This is the chunker's half of the citation guarantee (the store round-trip is
        // the other half) — a page mix-up here would cite the wrong page.
        String pageOneText = joinTextForPage(chunks, 1);
        String pageTwoText = joinTextForPage(chunks, 2);
        assertThat(pageOneText).contains(PAGE_ONE_MARKER).doesNotContain(PAGE_TWO_MARKER);
        assertThat(pageTwoText).contains(PAGE_TWO_MARKER).doesNotContain(PAGE_ONE_MARKER);

        // The small chunk size really did split a page into multiple chunks.
        assertThat(chunks.stream().filter(c -> c.provenance().pageNumber() == 1).count())
                .isGreaterThan(1L);
    }

    private static List<Integer> indices(int count) {
        return java.util.stream.IntStream.range(0, count).boxed().toList();
    }

    private static String joinTextForPage(List<Chunk> chunks, int pageNumber) {
        return chunks.stream()
                .filter(c -> c.provenance().pageNumber() == pageNumber)
                .map(Chunk::text)
                .reduce("", (a, b) -> a + " " + b);
    }

    private static byte[] twoPagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            addPage(document, PAGE_ONE_MARKER);
            addPage(document, PAGE_TWO_MARKER);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void addPage(PDDocument document, String marker) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.setLeading(14.5f);
            content.beginText();
            content.newLineAtOffset(50, 720);
            for (int sentence = 1; sentence <= 12; sentence++) {
                content.showText(marker + " is a distinctive element on this page, in sentence number "
                        + sentence + ".");
                content.newLine();
            }
            content.endText();
        }
    }
}
