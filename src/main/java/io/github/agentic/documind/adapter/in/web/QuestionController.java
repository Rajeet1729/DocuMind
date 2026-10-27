package io.github.agentic.documind.adapter.in.web;

import io.github.agentic.documind.application.port.in.AskQuestionUseCase;
import io.github.agentic.documind.domain.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web adapter driving {@link AskQuestionUseCase}. Translates a JSON question into a
 * use-case call and the resulting {@link Answer} into JSON — no retrieval or citation
 * logic lives here. Depends on the inbound port, never on the service implementation.
 */
@RestController
public class QuestionController {

    private final AskQuestionUseCase askQuestion;

    public QuestionController(AskQuestionUseCase askQuestion) {
        this.askQuestion = askQuestion;
    }

    @PostMapping(path = "/api/questions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnswerResponse ask(@RequestBody QuestionRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must not be blank");
        }
        Answer answer = askQuestion.ask(request.question().strip());
        return AnswerResponse.from(answer);
    }
}
