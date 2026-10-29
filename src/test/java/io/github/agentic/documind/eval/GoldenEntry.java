package io.github.agentic.documind.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One golden-set question. The eval harness scores the live RAG pipeline against a
 * list of these.
 *
 * <p>A retrieval <em>hit</em> requires BOTH the correct source file and a page in
 * {@link #relevantPages} — matching on page alone would let a chunk from the wrong
 * document (same page number, different file) count as a hit and inflate the metric,
 * which matters here because the corpus has two documents.
 *
 * @param id             stable identifier, unique within the set
 * @param question       the question put to the pipeline
 * @param expectedAnswer the reference answer (used by the faithfulness judge, not by
 *                       hit-rate)
 * @param filename       the source document this question is answerable from; a hit
 *                       must come from this file
 * @param relevantPages  1-based pages that contain the answer; <b>empty means
 *                       should-return-nothing</b> (the pipeline ought to abstain)
 * @param type           {@code factual}, {@code paraphrased}, or
 *                       {@code should-return-nothing} — the breakdown dimension
 */
public record GoldenEntry(
		@JsonProperty("id") String id,
		@JsonProperty("question") String question,
		@JsonProperty("expectedAnswer") String expectedAnswer,
		@JsonProperty("filename") String filename,
		@JsonProperty("relevantPages") List<Integer> relevantPages,
		@JsonProperty("type") String type) {

	public GoldenEntry {
		relevantPages = relevantPages == null ? List.of() : List.copyOf(relevantPages);
	}

	/** An entry with no relevant pages: a correct pipeline abstains rather than answers. */
	public boolean isShouldReturnNothing() {
		return relevantPages.isEmpty();
	}

	/**
	 * Whether a chunk retrieved from {@code retrievedFilename}/{@code retrievedPage} counts
	 * as a hit for this entry: a hit requires BOTH the correct file AND a relevant page.
	 */
	public boolean isHit(String retrievedFilename, int retrievedPage) {
		return filename.equals(retrievedFilename) && relevantPages.contains(retrievedPage);
	}
}
