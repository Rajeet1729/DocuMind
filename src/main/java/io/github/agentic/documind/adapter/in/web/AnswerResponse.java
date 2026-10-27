package io.github.agentic.documind.adapter.in.web;

import io.github.agentic.documind.domain.Answer;

import java.util.List;

/**
 * Response body for {@code POST /api/questions}: the generated text plus the citations
 * for the chunks that grounded it. Maps the domain {@link Answer} into the web layer's
 * own shape so the JSON contract is decoupled from the domain records.
 *
 * @param text      the generated answer
 * @param citations the sources the answer was grounded in
 */
public record AnswerResponse(String text, List<CitationResponse> citations) {

    public static AnswerResponse from(Answer answer) {
        List<CitationResponse> citations = answer.citations().stream()
                .map(c -> new CitationResponse(c.filename(), c.pageNumber(), c.snippet()))
                .toList();
        return new AnswerResponse(answer.text(), citations);
    }

    /**
     * @param filename   source document the cited text came from
     * @param pageNumber 1-based page within that document
     * @param snippet    short excerpt of the cited chunk
     */
    public record CitationResponse(String filename, int pageNumber, String snippet) {
    }
}
