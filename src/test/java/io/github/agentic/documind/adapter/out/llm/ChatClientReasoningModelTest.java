package io.github.agentic.documind.adapter.out.llm;

import io.github.agentic.documind.application.port.out.ReasoningModel.Sufficiency;
import io.github.agentic.documind.application.port.out.ReasoningModel.SufficiencyVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing and prompt assembly for the reasoning adapter, against a stub {@link ChatModel}
 * that returns a canned reply — offline, no API key.
 */
class ChatClientReasoningModelTest {

    private final List<Prompt> prompts = new ArrayList<>();

    private ChatClientReasoningModel replying(String reply) {
        ChatModel model = prompt -> {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        };
        return new ChatClientReasoningModel(ChatClient.create(model));
    }

    @Test
    void parsesBothVerdictsAndKeepsMissingInfoOnlyWhenInsufficient() {
        assertThat(ChatClientReasoningModel.parseVerdict("{\"verdict\": \"SUFFICIENT\", \"missing\": \"\"}"))
                .isEqualTo(new SufficiencyVerdict(Sufficiency.SUFFICIENT, ""));
        assertThat(ChatClientReasoningModel.parseVerdict("{\"verdict\": \"INSUFFICIENT\", \"missing\": \"the dose\"}"))
                .isEqualTo(new SufficiencyVerdict(Sufficiency.INSUFFICIENT, "the dose"));
    }

    @Test
    void toleratesFencesAndProseAroundTheJson() {
        String reply = "Here you go:\n```json\n{\"verdict\": \"insufficient\", \"missing\": \"x\"}\n```";

        assertThat(ChatClientReasoningModel.parseVerdict(reply).sufficiency()).isEqualTo(Sufficiency.INSUFFICIENT);
    }

    @Test
    void anythingUnreadableIsUnparseableNeverAGuess() {
        assertThat(ChatClientReasoningModel.parseVerdict("SUFFICIENT").sufficiency()).isEqualTo(Sufficiency.UNPARSEABLE);
        assertThat(ChatClientReasoningModel.parseVerdict("{\"verdict\": \"maybe\"}").sufficiency())
                .isEqualTo(Sufficiency.UNPARSEABLE);
        assertThat(ChatClientReasoningModel.parseVerdict("{not json}").sufficiency()).isEqualTo(Sufficiency.UNPARSEABLE);
    }

    @Test
    void cleanQueryTakesTheFirstNonBlankLineWithoutQuotes() {
        assertThat(ChatClientReasoningModel.cleanQuery("\n  \"QQRT framework sleep\"  \nbecause...")).isEqualTo("QQRT framework sleep");
        assertThat(ChatClientReasoningModel.cleanQuery("   ")).isEmpty();
    }

    @Test
    void gateSendsTheSystemPromptQuestionAndContext() {
        SufficiencyVerdict verdict = replying("{\"verdict\": \"SUFFICIENT\"}")
                .judgeSufficiency("What is QQRT?", "[1] Quality, Quantity...");

        assertThat(verdict.sufficiency()).isEqualTo(Sufficiency.SUFFICIENT);
        String sent = prompts.get(0).getContents();
        assertThat(sent).contains(ChatClientReasoningModel.SUFFICIENCY_SYSTEM_PROMPT)
                .contains("What is QQRT?").contains("[1] Quality, Quantity...");
    }

    @Test
    void reformulationSendsMissingInfoAndTriedQueries() {
        String query = replying("QQRT quality quantity regularity timing").reformulate(
                "What is QQRT?", "what the letters stand for", List.of("What is QQRT?"));

        assertThat(query).isEqualTo("QQRT quality quantity regularity timing");
        String sent = prompts.get(0).getContents();
        assertThat(sent).contains("what the letters stand for").contains("QUERIES ALREADY TRIED:\nWhat is QQRT?");
    }
}
