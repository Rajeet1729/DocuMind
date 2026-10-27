package io.github.agentic.documind.adapter.in.web;

/**
 * Request body for {@code POST /api/questions}. Web-layer shape, deliberately separate
 * from any domain type. Validation of a blank question happens in the controller.
 *
 * @param question the natural-language question to answer
 */
public record QuestionRequest(String question) {
}
