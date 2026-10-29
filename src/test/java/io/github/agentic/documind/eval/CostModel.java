package io.github.agentic.documind.eval;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Estimated cost accounting for a Slice-2 eval run.
 *
 * <p>Two confidence levels, kept visibly distinct in the printout:
 * <ul>
 *   <li><b>Estimated</b> — embeddings and answer generation carry no usage metadata back
 *       to the harness (embedding happens inside the vector store; the generated answer
 *       comes back as a plain domain {@code Answer}). Their token counts are estimated
 *       with jtokkit: {@code cl100k_base} for {@code text-embedding-3-small}, and
 *       {@code o200k_base} for the gpt-6 chat model. Generation input adds a small fixed
 *       allowance for the system prompt and message framing the harness cannot see.</li>
 *   <li><b>Measured</b> — every judge call is made through this harness's own
 *       {@link Judge} client, so each returns the API's real {@code promptTokens} /
 *       {@code completionTokens}. Judge cost is therefore measured, not estimated.</li>
 * </ul>
 *
 * <p>Prices are USD per 1M tokens, per third-party pricing aggregators checked around
 * Oct 2026: gpt-6-luna $0.10 in / $0.50 out, gpt-6-sol $2.00 in / $10.00 out,
 * text-embedding-3-small $0.02 in. They are <b>not</b> from OpenAI's own pricing page —
 * treat the dollar totals as indicative and verify against OpenAI before relying on them
 * for billing. The generation model is fixed (gpt-6-luna); the judge model is whatever
 * the harness was configured with, so judge pricing is looked up by its id.
 */
final class CostModel {

	static final String GEN_MODEL = "gpt-6-luna";
	static final String EMBED_MODEL = "text-embedding-3-small";

	static final double EMBED_USD_PER_M = 0.02;

	/** Chat-model rates (USD per 1M tokens, in/out). Extend as judges are added. */
	private static final Map<String, Rate> CHAT_RATES = Map.of(
			"gpt-6-luna", new Rate(0.10, 0.50),
			"gpt-6-sol", new Rate(2.00, 10.00));

	private record Rate(double inPerM, double outPerM) {
	}

	/**
	 * Fixed tokens the harness cannot observe on a generation call: the service's system
	 * prompt plus the "Context:/Question:" template wording and the chat-message framing.
	 * An approximation, added to every estimated generation-input count.
	 */
	static final int GEN_SCAFFOLD_TOKENS = 70;

	private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
	private final Encoding chatEnc = registry.getEncoding(EncodingType.O200K_BASE);
	private final Encoding embedEnc = registry.getEncoding(EncodingType.CL100K_BASE);

	private final String judgeModel;

	/** @param judgeModel the configured judge model id; its rates price the judge calls */
	CostModel(String judgeModel) {
		this.judgeModel = judgeModel;
	}

	// Estimated.
	private long embedCorpusTokens;
	private long embedQueryTokens;
	private int embedQueryCalls;
	private long genInputTokens;
	private long genOutputTokens;
	private int genCalls;

	// Measured (from API usage).
	private long judgeInputTokens;
	private long judgeOutputTokens;
	private int judgeCalls;

	/** Token count for a chat-model string (o200k_base). */
	int chatTokens(String text) {
		return text == null ? 0 : chatEnc.countTokens(text);
	}

	/** Corpus embedding: the whole chunk corpus is embedded once at ingestion. */
	void addCorpusEmbedding(String chunkText) {
		embedCorpusTokens += embedEnc.countTokens(chunkText == null ? "" : chunkText);
	}

	/** One query embedding (the query string is embedded on each {@code search}). */
	void addQueryEmbedding(String query) {
		embedQueryTokens += embedEnc.countTokens(query == null ? "" : query);
		embedQueryCalls++;
	}

	/** One generation call: estimated input (context + question + scaffold) and output. */
	void addGeneration(int contextAndQuestionTokens, String answerText) {
		genInputTokens += contextAndQuestionTokens + GEN_SCAFFOLD_TOKENS;
		genOutputTokens += chatTokens(answerText);
		genCalls++;
	}

	/** One judge call: real token usage from the API response. */
	void addJudge(int promptTokens, int completionTokens) {
		judgeInputTokens += promptTokens;
		judgeOutputTokens += completionTokens;
		judgeCalls++;
	}

	private static Rate rate(String model) {
		return CHAT_RATES.get(model);
	}

	/** USD for a chat call at {@code model}'s rates, or null if the model has no rate on file. */
	static Double chatUsd(String model, long inputTokens, long outputTokens) {
		Rate r = rate(model);
		if (r == null) {
			return null;
		}
		return inputTokens / 1_000_000.0 * r.inPerM() + outputTokens / 1_000_000.0 * r.outPerM();
	}

	/** jtokkit estimate of chat-model (o200k_base) tokens; shared with the dual-judge pass. */
	int estimateChatTokens(String text) {
		return chatTokens(text);
	}

	/** jtokkit estimate of embedding-model (cl100k_base) tokens. */
	int estimateEmbedTokens(String text) {
		return text == null ? 0 : embedEnc.countTokens(text);
	}

	private double embeddingUsd() {
		return (embedCorpusTokens + embedQueryTokens) / 1_000_000.0 * EMBED_USD_PER_M;
	}

	private double generationUsd() {
		Rate r = rate(GEN_MODEL);
		return genInputTokens / 1_000_000.0 * r.inPerM() + genOutputTokens / 1_000_000.0 * r.outPerM();
	}

	/** null if the judge model has no known rate (dollar total then omitted, not guessed). */
	private Double judgeUsd() {
		Rate r = rate(judgeModel);
		if (r == null) {
			return null;
		}
		return judgeInputTokens / 1_000_000.0 * r.inPerM() + judgeOutputTokens / 1_000_000.0 * r.outPerM();
	}

	/** The cost summary as text lines, shared by the console print and the persisted file. */
	List<String> summaryLines() {
		double embed = embeddingUsd();
		double gen = generationUsd();
		Double judge = judgeUsd();
		Rate genRate = rate(GEN_MODEL);
		Rate judgeRate = rate(judgeModel);

		List<String> out = new ArrayList<>();
		out.add(" ESTIMATED COST SUMMARY");
		out.add("   prices USD/1M tokens — " + GEN_MODEL + ": $" + genRate.inPerM()
				+ " in / $" + genRate.outPerM() + " out;  judge " + judgeModel + ": "
				+ (judgeRate == null ? "UNKNOWN rate" : "$" + judgeRate.inPerM() + " in / $" + judgeRate.outPerM() + " out")
				+ ";  " + EMBED_MODEL + ": $" + EMBED_USD_PER_M + " in");
		out.add("   (aggregator prices ~Oct 2026 — verify against OpenAI before billing)");
		out.add("");
		out.add(String.format("   %-26s %10s %10s %12s", "component", "in tok", "out tok", "est. USD"));
		out.add(String.format("   %-26s %10d %10s %12s   [est]", "embeddings: corpus", embedCorpusTokens, "-", ""));
		out.add(String.format("   %-26s %10d %10s %12s   [est] %d calls",
				"embeddings: queries", embedQueryTokens, "-", "", embedQueryCalls));
		out.add(String.format("   %-26s %10s %10s %12s", "  embeddings subtotal", "", "", usd(embed)));
		out.add(String.format("   %-26s %10d %10d %12s   [est] %d calls",
				"generation (" + GEN_MODEL + ")", genInputTokens, genOutputTokens, usd(gen), genCalls));
		out.add(String.format("   %-26s %10d %10d %12s   [measured] %d calls",
				"judge (" + judgeModel + ")", judgeInputTokens, judgeOutputTokens,
				judge == null ? "n/a" : usd(judge), judgeCalls));
		out.add("   " + "-".repeat(62));
		out.add(String.format("   %-26s %10s %10s %12s", "TOTAL", "", "",
				judge == null ? "n/a" : usd(embed + gen + judge)));
		out.add("");
		out.add("   generation input includes a ~" + GEN_SCAFFOLD_TOKENS
				+ "-token/call fixed prompt+framing allowance the harness cannot observe.");
		out.add("   embeddings & generation are jtokkit ESTIMATES; judge tokens are MEASURED.");
		if (judgeRate == null) {
			out.add("   ! no rate on file for judge model '" + judgeModel
					+ "' — judge $ omitted; add it to CostModel.CHAT_RATES.");
		}
		return out;
	}

	void printSummary() {
		System.out.println("------------------------------------------------------------------");
		summaryLines().forEach(System.out::println);
	}

	private static String usd(double amount) {
		return String.format("$%.4f", amount);
	}
}
