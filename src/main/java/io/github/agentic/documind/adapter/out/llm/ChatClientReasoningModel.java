package io.github.agentic.documind.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.documind.application.port.out.ReasoningModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ReasoningModel} over the application's Spring AI {@link ChatClient} — so the
 * gate and reformulation run on the same model as answer generation ({@code gpt-6-luna}).
 *
 * <p>No temperature is set: {@code gpt-6-luna} rejects any value but the default (1),
 * verified against the live API. The loop's non-determinism is therefore measured
 * (multi-run stability in the eval), not dampened.
 *
 * <p>The system prompts are {@code public static final} so the eval harness can log them
 * verbatim. The gate asks for a bare JSON object that we parse ourselves (tolerating
 * fences or stray prose), rather than through Spring AI's structured-output converter,
 * which would append its own instructions and make the logged prompt differ from the one
 * sent. An unreadable gate reply becomes {@code UNPARSEABLE}, which the loop treats as
 * "stop and use the context in hand" — it never loops on garbage.
 */
@Component
public class ChatClientReasoningModel implements ReasoningModel {

    /** Sufficiency gate system prompt. Logged verbatim by the eval harness. */
    public static final String SUFFICIENCY_SYSTEM_PROMPT = """
            You check whether retrieved passages are enough to answer a question. You are \
            given a QUESTION and the numbered CONTEXT passages a retrieval system returned \
            for it. Use no outside knowledge.

            Choose exactly one verdict:
            - SUFFICIENT: the context contains the information needed to answer the question.
            - INSUFFICIENT: the information needed to answer the question is missing from \
            the context, in whole or in part.

            If INSUFFICIENT, say briefly what information is missing.

            Respond with ONLY a JSON object, no markdown fences, of exactly this shape:
            {"verdict": "SUFFICIENT" | "INSUFFICIENT", "missing": "<what is missing, or empty>"}""";

    /** Query reformulation system prompt. Logged verbatim by the eval harness. */
    public static final String REFORMULATION_SYSTEM_PROMPT = """
            You rewrite search queries for a document retrieval system that combines keyword \
            (BM25) and semantic (embedding) search. The queries already tried did not \
            retrieve the information needed to answer the QUESTION.

            Write ONE new search query more likely to retrieve passages containing the \
            MISSING information: use the specific terms, names, and synonyms likely to \
            appear in the source text. Do not repeat a query already tried.

            Respond with ONLY the query text on a single line — no quotes, no explanation.""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public ChatClientReasoningModel(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public SufficiencyVerdict judgeSufficiency(String question, String context) {
        String user = """
                QUESTION:
                %s

                CONTEXT:
                %s""".formatted(question, context);
        return parseVerdict(call(SUFFICIENCY_SYSTEM_PROMPT, user));
    }

    @Override
    public String reformulate(String question, String missingInfo, List<String> triedQueries) {
        String user = """
                QUESTION:
                %s

                MISSING:
                %s

                QUERIES ALREADY TRIED:
                %s""".formatted(question, missingInfo.isBlank() ? "(not stated)" : missingInfo,
                String.join("\n", triedQueries));
        return cleanQuery(call(REFORMULATION_SYSTEM_PROMPT, user));
    }

    private String call(String system, String user) {
        String text = chatClient.prompt().system(system).user(user).call().content();
        return text == null ? "" : text.strip();
    }

    /** Read the gate's JSON reply; anything unreadable is {@code UNPARSEABLE}, never a guess. */
    static SufficiencyVerdict parseVerdict(String reply) {
        JsonNode json = tryParse(reply);
        if (json == null || !json.hasNonNull("verdict")) {
            return new SufficiencyVerdict(Sufficiency.UNPARSEABLE, "");
        }
        String verdict = json.get("verdict").asText().strip().toUpperCase();
        String missing = json.path("missing").asText("");
        // INSUFFICIENT contains SUFFICIENT, so test it first.
        if (verdict.contains("INSUFFICIENT")) {
            return new SufficiencyVerdict(Sufficiency.INSUFFICIENT, missing);
        }
        if (verdict.contains("SUFFICIENT")) {
            return new SufficiencyVerdict(Sufficiency.SUFFICIENT, "");
        }
        return new SufficiencyVerdict(Sufficiency.UNPARSEABLE, "");
    }

    /** First non-blank line, with any wrapping quotes removed; empty if there is none. */
    static String cleanQuery(String reply) {
        for (String line : reply.strip().split("\n")) {
            String q = line.strip();
            if (q.length() >= 2 && (q.startsWith("\"") && q.endsWith("\"") || q.startsWith("'") && q.endsWith("'"))) {
                q = q.substring(1, q.length() - 1).strip();
            }
            if (!q.isEmpty()) {
                return q;
            }
        }
        return "";
    }

    /** Parse a JSON object from the reply, tolerating markdown fences or surrounding prose. */
    private static JsonNode tryParse(String text) {
        String candidate = text.strip();
        int open = candidate.indexOf('{');
        int close = candidate.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return null;
        }
        try {
            return MAPPER.readTree(candidate.substring(open, close + 1));
        }
        catch (Exception e) {
            return null;
        }
    }
}
