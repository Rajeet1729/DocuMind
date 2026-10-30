package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.in.AskQuestionUseCase;
import io.github.agentic.documind.domain.Answer;
import io.github.agentic.documind.domain.Citation;
import io.github.agentic.documind.domain.Provenance;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Drives the ask use case: retrieve, ground, answer.
 *
 * <p><b>Citations come from the retrieved context, not from the model's reply.</b> We
 * place the top-K chunks into a numbered context block and derive one {@link Citation}
 * per chunk from its provenance. The model is told to ground its answer in that context
 * and to cite by number, but the citations we return are the provenance of what we put
 * in front of it — never something parsed out of its free text. "Which sources the
 * model actually used" is a later slice.
 *
 * <p>Context building and citation derivation are plain, deterministic methods so they
 * can be unit-tested without a model; the {@link ChatClient} is injected directly so it
 * can be mocked in the orchestration tests.
 *
 * <p>How the context is assembled is the {@link RetrievalStrategy}'s job (single-shot by
 * default, the agentic loop behind a flag). Either way it hands back at most {@code topK}
 * chunks and this service grounds on them with the same prompt.
 */
@Service
public class QuestionAnsweringService implements AskQuestionUseCase {

    static final String NO_CONTEXT_ANSWER =
            "I couldn't find anything relevant in the ingested documents to answer that.";

    private static final int SNIPPET_MAX_CHARS = 300;

    private static final String SYSTEM_PROMPT = """
            You are a precise assistant. Answer the user's question using ONLY the numbered
            context passages provided. If the answer is not contained in the context, say you
            don't know. Cite the passages you rely on by their number, e.g. [1].""";

    private final RetrievalStrategy retrievalStrategy;
    private final ChatClient chatClient;
    private final int topK;

    public QuestionAnsweringService(RetrievalStrategy retrievalStrategy, ChatClient chatClient,
                                    @Value("${documind.retrieval.top-k:4}") int topK) {
        this.retrievalStrategy = retrievalStrategy;
        this.chatClient = chatClient;
        this.topK = topK;
    }

    @Override
    public Answer ask(String question) {
        List<RetrievedChunk> retrieved = retrievalStrategy.retrieve(question, topK).context();
        if (retrieved.isEmpty()) {
            // Nothing to ground on: return a canned answer rather than prompt the model
            // without context (which would invite an ungrounded, uncitable reply).
            return new Answer(NO_CONTEXT_ANSWER, List.of());
        }

        String answerText = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt(question, buildContext(retrieved)))
                .call()
                .content();

        return new Answer(answerText, toCitations(retrieved));
    }

    /** Numbered, 1-based context block aligning with the citation numbers. */
    static String buildContext(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append('[').append(i + 1).append("] ").append(chunks.get(i).chunk().text());
        }
        return sb.toString();
    }

    /** One citation per context chunk, taken straight from provenance (Fix #2). */
    static List<Citation> toCitations(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(retrieved -> {
                    Provenance p = retrieved.chunk().provenance();
                    return new Citation(p.filename(), p.pageNumber(), snippet(retrieved.chunk().text()));
                })
                .toList();
    }

    static String snippet(String text) {
        String trimmed = text.strip();
        if (trimmed.length() <= SNIPPET_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_MAX_CHARS).stripTrailing() + "…";
    }

    static String userPrompt(String question, String context) {
        return """
                Context:
                %s

                Question: %s""".formatted(context, question);
    }
}
