package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.domain.Answer;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.Citation;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuestionAnsweringServiceTest {

    private final ChunkStore chunkStore = mock(ChunkStore.class);
    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final QuestionAnsweringService service =
            new QuestionAnsweringService(new SingleShotRetrieval(chunkStore), chatClient, 3);

    private static RetrievedChunk retrieved(String text, String file, int page, int index, double score) {
        return new RetrievedChunk(new Chunk(text, new Provenance("doc", file, page, index)), score);
    }

    // --- pure, model-free methods ---

    @Test
    void buildContextNumbersPassagesOneBased() {
        String context = QuestionAnsweringService.buildContext(List.of(
                retrieved("alpha", "a.pdf", 1, 0, 0.9),
                retrieved("beta", "a.pdf", 2, 1, 0.8)));

        assertThat(context).isEqualTo("[1] alpha\n\n[2] beta");
    }

    @Test
    void citationsAreDerivedFromProvenanceOnePerChunk() {
        List<Citation> citations = QuestionAnsweringService.toCitations(List.of(
                retrieved("alpha text", "a.pdf", 1, 0, 0.9),
                retrieved("beta text", "b.pdf", 7, 3, 0.8)));

        assertThat(citations).containsExactly(
                new Citation("a.pdf", 1, "alpha text"),
                new Citation("b.pdf", 7, "beta text"));
    }

    @Test
    void snippetTruncatesLongText() {
        String snippet = QuestionAnsweringService.snippet("x".repeat(400));

        assertThat(snippet).hasSize(301).endsWith("…");
    }

    // --- orchestration ---

    @Test
    void askGroundsOnRetrievedChunksAndCitesTheirProvenanceNotTheModelText() {
        List<RetrievedChunk> hits = List.of(
                retrieved("Paris is the capital of France.", "france.pdf", 1, 0, 0.9),
                retrieved("It has about two million residents.", "france.pdf", 2, 1, 0.7));
        when(chunkStore.search("capital of France?", 3)).thenReturn(hits);
        // The model reply deliberately contains no citation markers: citations must still
        // come from the retrieved context's provenance, not from this text.
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("Paris.");

        Answer answer = service.ask("capital of France?");

        assertThat(answer.text()).isEqualTo("Paris.");
        assertThat(answer.citations()).containsExactly(
                new Citation("france.pdf", 1, "Paris is the capital of France."),
                new Citation("france.pdf", 2, "It has about two million residents."));
        verify(chunkStore).search("capital of France?", 3);
    }

    @Test
    void askReturnsCannedAnswerAndNeverCallsModelWhenNothingRetrieved() {
        when(chunkStore.search(eq("unknown?"), anyInt())).thenReturn(List.of());

        Answer answer = service.ask("unknown?");

        assertThat(answer.text()).isEqualTo(QuestionAnsweringService.NO_CONTEXT_ANSWER);
        assertThat(answer.citations()).isEmpty();
        verifyNoInteractions(chatClient);
    }
}
