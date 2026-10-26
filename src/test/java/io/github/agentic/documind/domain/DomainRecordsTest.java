package io.github.agentic.documind.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the invariants the domain records actually enforce. Plain data records
 * (Chunk, RetrievedChunk) and interface ports have no behaviour worth testing here.
 */
class DomainRecordsTest {

    @Test
    void provenanceRejectsNonPositivePageNumber() {
        assertThatThrownBy(() -> new Provenance("doc-1", "spec.pdf", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageNumber");
    }

    @Test
    void provenanceRejectsBlankIdentifiers() {
        assertThatThrownBy(() -> new Provenance("  ", "spec.pdf", 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId");
        assertThatThrownBy(() -> new Provenance("doc-1", "", 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename");
    }

    @Test
    void provenanceRejectsNegativeChunkIndex() {
        assertThatThrownBy(() -> new Provenance("doc-1", "spec.pdf", 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkIndex");
    }

    @Test
    void citationRejectsNonPositivePageNumber() {
        assertThatThrownBy(() -> new Citation("spec.pdf", 0, "..."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageNumber");
    }

    @Test
    void answerCopiesCitationsDefensively() {
        List<Citation> mutable = new ArrayList<>();
        mutable.add(new Citation("spec.pdf", 1, "hello"));

        Answer answer = new Answer("grounded reply", mutable);

        // Mutating the source list must not leak into the answer.
        mutable.add(new Citation("spec.pdf", 2, "later"));
        assertThat(answer.citations()).hasSize(1);

        // And the stored list is itself unmodifiable.
        assertThatThrownBy(() -> answer.citations().add(new Citation("x.pdf", 1, "nope")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
