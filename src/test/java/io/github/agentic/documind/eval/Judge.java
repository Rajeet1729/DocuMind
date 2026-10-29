package io.github.agentic.documind.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * LLM-as-judge for the Slice-2 faithfulness step. Wraps a {@link ChatClient} pinned to a
 * single judge model ({@code gpt-6-luna}) and exposes two independent judgements:
 *
 * <ul>
 *   <li><b>Faithfulness</b> (factual + paraphrased entries) — is the generated answer
 *       <em>grounded in</em> the retrieved context? The judge sees the <b>full retrieved
 *       chunk text</b>, not the truncated citation snippets, and is told to ignore any
 *       outside notion of a "correct" answer: a claim counts only if the context
 *       supports it. Verdict is {@code SUPPORTED} / {@code PARTIAL} / {@code UNSUPPORTED}
 *       plus a short rationale.</li>
 *   <li><b>Abstention</b> (should-return-nothing entries) — did the pipeline correctly
 *       decline instead of fabricating an answer? Scored as its own metric, kept out of
 *       the faithfulness headline.</li>
 * </ul>
 *
 * <p>The two system prompts are {@code public static final} so the harness can log them
 * <b>verbatim</b>. The prompts ask for a bare JSON object (no markdown fences) which we
 * parse ourselves rather than via Spring AI's structured-output converter — that keeps
 * the prompt we log identical to the prompt actually sent (the converter would append
 * its own format instructions). Each call captures the model's <b>real</b> token usage
 * from the API response, so judge cost is measured, not estimated.
 */
final class Judge {

	/** Faithfulness judge system prompt. Logged verbatim by the harness. */
	static final String FAITHFULNESS_SYSTEM_PROMPT = """
			You are a strict evaluator of answer faithfulness for a retrieval-augmented \
			question-answering system. You are given a QUESTION, the numbered CONTEXT \
			passages that were retrieved and placed in front of the answering model, and \
			the ANSWER that model produced.

			Judge ONLY whether the ANSWER is faithful to (grounded in) the CONTEXT. Use no \
			outside knowledge. Do not reward or penalise the answer for matching some other \
			"correct" answer you have in mind — a claim counts as faithful only if the \
			CONTEXT supports it.

			Choose exactly one verdict:
			- SUPPORTED: every factual claim in the answer is supported by the context.
			- PARTIAL: the answer is partly grounded but adds, overstates, or mixes in at \
			least one claim the context does not support.
			- UNSUPPORTED: the answer's main claim is not supported by the context \
			(fabrication, or a confident answer drawn from an unrelated passage). An answer \
			that correctly declines ("I don't know", "not in the provided context") is \
			SUPPORTED, because declining asserts nothing unsupported.

			Respond with ONLY a JSON object, no markdown fences, of exactly this shape:
			{"verdict": "SUPPORTED" | "PARTIAL" | "UNSUPPORTED", "rationale": "<one or two sentences>"}""";

	/** Abstention judge system prompt. Logged verbatim by the harness. */
	static final String ABSTENTION_SYSTEM_PROMPT = """
			You are evaluating whether a question-answering system correctly ABSTAINED. \
			Each question below is known to be unanswerable from the ingested documents, so \
			the only correct behaviour is to decline — to say it does not know, or that the \
			information is not in the documents — rather than produce a substantive answer.

			You are given the QUESTION and the system's ANSWER. Choose exactly one:
			- ABSTAINED (abstained=true): the answer declines, says it cannot find the \
			information, says it is not in the documents, or otherwise gives no substantive \
			factual answer to the question.
			- ANSWERED (abstained=false): the answer provides a substantive factual answer \
			to the question, whether or not that answer is actually correct. This is a \
			failure to abstain.

			Respond with ONLY a JSON object, no markdown fences, of exactly this shape:
			{"abstained": true | false, "rationale": "<one or two sentences>"}""";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ChatClient judgeClient;
	private final String judgeModel;

	/**
	 * @param builder    the auto-configured Spring AI chat client builder
	 * @param judgeModel the model id to pin for every judge call (e.g. {@code gpt-6-luna});
	 *                   set explicitly here so the judge is independent of the app's
	 *                   configured generation model and so the pinned id is logged
	 */
	Judge(ChatClient.Builder builder, String judgeModel) {
		this.judgeModel = judgeModel;
		// clone() so two judges (e.g. the dual-judge A/B) don't share mutable builder state.
		this.judgeClient = builder.clone().defaultOptions(ChatOptions.builder().model(judgeModel)).build();
	}

	String judgeModel() {
		return judgeModel;
	}

	/** Grade the answer's faithfulness to the full retrieved context. */
	FaithfulnessJudgement judgeFaithfulness(String question, String fullContext, String answer) {
		String user = """
				QUESTION:
				%s

				CONTEXT:
				%s

				ANSWER:
				%s""".formatted(question, fullContext, answer);

		JudgeCall call = call(FAITHFULNESS_SYSTEM_PROMPT, user);
		Grade grade = Grade.UNPARSEABLE;
		String rationale = "(could not parse judge response)";
		JsonNode json = tryParse(call.text());
		if (json != null && json.hasNonNull("verdict")) {
			grade = parseGrade(json.get("verdict").asText());
			rationale = json.path("rationale").asText("");
		}
		return new FaithfulnessJudgement(grade, rationale, call.promptTokens(),
				call.completionTokens(), call.reportedModel(), call.text());
	}

	/** Decide whether the pipeline correctly abstained on an unanswerable question. */
	AbstentionJudgement judgeAbstention(String question, String answer) {
		String user = """
				QUESTION:
				%s

				ANSWER:
				%s""".formatted(question, answer);

		JudgeCall call = call(ABSTENTION_SYSTEM_PROMPT, user);
		JsonNode json = tryParse(call.text());
		boolean parsed = json != null && json.has("abstained");
		boolean abstained = parsed && json.get("abstained").asBoolean(false);
		String rationale = parsed ? json.path("rationale").asText("") : "(could not parse judge response)";
		return new AbstentionJudgement(abstained, parsed, rationale, call.promptTokens(),
				call.completionTokens(), call.reportedModel(), call.text());
	}

	// --- model call + parsing ------------------------------------------------------

	private JudgeCall call(String system, String user) {
		ChatResponse response = judgeClient.prompt()
				.system(system)
				.user(user)
				.call()
				.chatResponse();
		String text = response.getResult().getOutput().getText();
		Usage usage = response.getMetadata().getUsage();
		return new JudgeCall(text == null ? "" : text.strip(),
				tokens(usage == null ? null : usage.getPromptTokens()),
				tokens(usage == null ? null : usage.getCompletionTokens()),
				response.getMetadata().getModel());
	}

	private static int tokens(Integer value) {
		return value == null ? 0 : value;
	}

	private static Grade parseGrade(String raw) {
		String v = raw.strip().toUpperCase();
		if (v.contains("UNSUPPORTED")) {
			return Grade.UNSUPPORTED;
		}
		if (v.contains("PARTIAL")) {
			return Grade.PARTIAL;
		}
		if (v.contains("SUPPORTED")) {
			return Grade.SUPPORTED;
		}
		return Grade.UNPARSEABLE;
	}

	/** Parse a JSON object from the reply, tolerating stray markdown fences or prose. */
	private static JsonNode tryParse(String text) {
		String candidate = stripFences(text);
		int open = candidate.indexOf('{');
		int close = candidate.lastIndexOf('}');
		if (open >= 0 && close > open) {
			candidate = candidate.substring(open, close + 1);
		}
		try {
			return MAPPER.readTree(candidate);
		}
		catch (Exception e) {
			return null;
		}
	}

	private static String stripFences(String text) {
		String t = text.strip();
		if (t.startsWith("```")) {
			int firstNewline = t.indexOf('\n');
			if (firstNewline > 0) {
				t = t.substring(firstNewline + 1);
			}
			if (t.endsWith("```")) {
				t = t.substring(0, t.length() - 3);
			}
		}
		return t.strip();
	}

	/** Faithfulness grades. {@code UNPARSEABLE} is a data-quality bucket, never SUPPORTED. */
	enum Grade {
		SUPPORTED, PARTIAL, UNSUPPORTED, UNPARSEABLE
	}

	/** One faithfulness judgement plus the real token usage the API reported. */
	record FaithfulnessJudgement(Grade grade, String rationale, int promptTokens,
			int completionTokens, String reportedModel, String raw) {
	}

	/** One abstention judgement plus the real token usage the API reported. */
	record AbstentionJudgement(boolean abstained, boolean parsed, String rationale,
			int promptTokens, int completionTokens, String reportedModel, String raw) {
	}

	/** Raw result of a single judge call before verdict parsing. */
	private record JudgeCall(String text, int promptTokens, int completionTokens, String reportedModel) {
	}
}
