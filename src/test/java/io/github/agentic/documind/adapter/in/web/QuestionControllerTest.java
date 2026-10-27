package io.github.agentic.documind.adapter.in.web;

import io.github.agentic.documind.application.port.in.AskQuestionUseCase;
import io.github.agentic.documind.domain.Answer;
import io.github.agentic.documind.domain.Citation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuestionController.class)
class QuestionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AskQuestionUseCase askQuestion;

    @Test
    void passesQuestionThroughAndSerializesAnswerWithCitations() throws Exception {
        when(askQuestion.ask("capital of France?")).thenReturn(new Answer(
                "Paris.",
                List.of(new Citation("france.pdf", 1, "Paris is the capital of France."))));

        mockMvc.perform(post("/api/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"capital of France?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Paris."))
                .andExpect(jsonPath("$.citations[0].filename").value("france.pdf"))
                .andExpect(jsonPath("$.citations[0].pageNumber").value(1))
                .andExpect(jsonPath("$.citations[0].snippet").value("Paris is the capital of France."));

        verify(askQuestion).ask("capital of France?");
    }

    @Test
    void blankQuestionIsRejectedWith400AndNeverReachesTheUseCase() throws Exception {
        mockMvc.perform(post("/api/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(askQuestion);
    }
}
