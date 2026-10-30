package io.github.agentic.documind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.documind.TestcontainersConfiguration;
import io.github.agentic.documind.application.port.in.AskQuestionUseCase;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.DocumentChunker;
import io.github.agentic.documind.domain.Answer;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.RetrievedChunk;
import io.github.agentic.documind.eval.Judge.AbstentionJudgement;
import io.github.agentic.documind.eval.Judge.FaithfulnessJudgement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-2 <b>fixed-answer dual-judge A/B</b> — a deliberate, standalone experiment, not
 * part of a normal run.
 *
 * <p>Goal: isolate the judge model as the only variable. {@link FaithfulnessEvaluation}
 * compares judges across <em>two</em> runs, so each judge scores a different (regenerated,
 * non-deterministic) answer set; here we generate <b>one</b> answer set, freeze it, and
 * run <b>both</b> judges ({@code gpt-6-luna} and {@code gpt-6-sol}) over the identical
 * frozen answers. Any per-question disagreement is then attributable to the judge alone.
 *
 * <p>Reproducibility: the frozen answers (question + full retrieved chunk text + the
 * generated answer) and both judges' verdicts are persisted as JSON under
 * {@code target/eval/}. Point a later run at that file
 * ({@code -Ddocumind.eval.frozen-answers=<path>}) to re-judge the <em>same</em> answers
 * without paying to regenerate them — generation and embedding cost then drop to zero.
 *
 * <p>Run it on demand: {@code ./mvnw test -Peval -Dtest=DualJudgeEvaluation}. The two
 * judge models are configurable ({@code documind.eval.judge-a} / {@code .judge-b}).
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class DualJudgeEvaluation {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private DocumentChunker chunker;

	@Autowired
	private ChunkStore chunkStore;

	@Autowired
	private AskQuestionUseCase askQuestion;

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Value("${documind.retrieval.top-k:4}")
	private int topK;

	@Value("${spring.ai.openai.chat.model:unknown}")
	private String generationModel;

	@Value("${documind.eval.judge-a:gpt-6-luna}")
	private String judgeAModel;

	@Value("${documind.eval.judge-b:gpt-6-sol}")
	private String judgeBModel;

	/** Optional: reuse a prior run's frozen answers instead of regenerating. */
	@Value("${documind.eval.frozen-answers:}")
	private String frozenAnswersPath;

	@Value("${documind.retrieval.agentic.enabled:false}")
	private boolean agenticEnabled;

	private List<GoldenEntry> golden;
	private final List<String> ingestedFiles = new ArrayList<>();
	private final CostModel estimator = new CostModel("gpt-6-luna"); // used only for token estimates
	private boolean reuseFrozen;

	// Estimated generation/embedding cost (zero when answers are reused from a frozen file).
	private long embedTokens;
	private long genInputTokens;
	private long genOutputTokens;
	private int genCalls;

	@BeforeAll
	void maybeIngestCorpus() {
		assertThat(agenticEnabled).as("this harness re-derives the judge's context with a second "
				+ "search(), which only matches the pipeline's context when retrieval is deterministic "
				+ "single-shot; evaluate the agentic loop with AgenticEvaluation").isFalse();
		golden = GoldenSet.load();
		reuseFrozen = frozenAnswersPath != null && !frozenAnswersPath.isBlank()
				&& Files.exists(Path.of(frozenAnswersPath));
		if (reuseFrozen) {
			return; // no corpus needed — answers (and their context) come from the frozen file
		}
		golden.stream().map(GoldenEntry::filename).distinct().forEach(filename -> {
			ClassPathResource pdf = new ClassPathResource("eval/corpus/" + filename);
			if (!pdf.exists()) {
				return;
			}
			byte[] bytes;
			try {
				bytes = pdf.getContentAsByteArray();
			}
			catch (IOException e) {
				throw new IllegalStateException("could not read corpus file " + filename, e);
			}
			List<Chunk> chunks = chunker.chunk(UUID.randomUUID().toString(), filename, bytes);
			chunkStore.save(chunks);
			ingestedFiles.add(filename);
			chunks.forEach(c -> embedTokens += estimator.estimateEmbedTokens(c.text()));
		});
	}

	@Test
	void dualJudgeOnFrozenAnswers() {
		Judge judgeA = new Judge(chatClientBuilder, judgeAModel);
		Judge judgeB = new Judge(chatClientBuilder, judgeBModel);

		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
				.withZone(ZoneOffset.UTC).format(Instant.now());

		List<Frozen> frozen = reuseFrozen ? loadFrozen(Path.of(frozenAnswersPath)) : generateAndFreeze();

		printHeader(stamp, frozen.size());

		long aIn = 0;
		long aOut = 0;
		long bIn = 0;
		long bOut = 0;
		int agreements = 0;
		List<Comparison> comparisons = new ArrayList<>();
		System.out.printf(" %-5s %-13s %-13s %-13s %-6s%n", "id", "type", judgeAModel, judgeBModel, "agree");

		for (Frozen f : frozen) {
			String verdictA;
			String verdictB;
			String rationaleA;
			String rationaleB;
			if (f.srn()) {
				AbstentionJudgement a = judgeA.judgeAbstention(f.question(), f.answer());
				AbstentionJudgement b = judgeB.judgeAbstention(f.question(), f.answer());
				verdictA = abstentionVerdict(a);
				verdictB = abstentionVerdict(b);
				rationaleA = a.rationale();
				rationaleB = b.rationale();
				aIn += a.promptTokens();
				aOut += a.completionTokens();
				bIn += b.promptTokens();
				bOut += b.completionTokens();
			}
			else {
				String context = numberedContext(f.retrieved());
				FaithfulnessJudgement a = judgeA.judgeFaithfulness(f.question(), context, f.answer());
				FaithfulnessJudgement b = judgeB.judgeFaithfulness(f.question(), context, f.answer());
				verdictA = a.grade().name();
				verdictB = b.grade().name();
				rationaleA = a.rationale();
				rationaleB = b.rationale();
				aIn += a.promptTokens();
				aOut += a.completionTokens();
				bIn += b.promptTokens();
				bOut += b.completionTokens();
			}
			boolean agree = verdictA.equals(verdictB);
			if (agree) {
				agreements++;
			}
			comparisons.add(new Comparison(f.id(), f.type(), f.srn(), verdictA, rationaleA, verdictB, rationaleB, agree));
			System.out.printf(" %-5s %-13s %-13s %-13s %-6s%n",
					f.id(), f.type(), verdictA, verdictB, agree ? "yes" : "NO <-");
		}

		List<String> costLines = costLines(aIn, aOut, bIn, bOut);
		printComparisonSummary(comparisons, agreements, costLines);

		Path jsonFile = Path.of("target", "eval", "dual-judge-" + stamp + ".json");
		writeJson(jsonFile, new DualJudgeDoc(stamp, generationModel, judgeAModel, judgeBModel,
				topK, reuseFrozen, frozen, comparisons, costLines));
		System.out.println(" persisted (frozen answers + both verdicts): " + jsonFile.toAbsolutePath());
		System.out.println("==================================================================");
	}

	// --- generation / freezing ------------------------------------------------------

	private List<Frozen> generateAndFreeze() {
		List<Frozen> frozen = new ArrayList<>();
		for (GoldenEntry e : golden) {
			if (!ingestedFiles.contains(e.filename())) {
				continue; // can't generate without the source file
			}
			List<RetrievedChunk> retrieved = chunkStore.search(e.question(), topK);
			embedTokens += estimator.estimateEmbedTokens(e.question()); // harness search()
			Answer answer = askQuestion.ask(e.question());
			embedTokens += estimator.estimateEmbedTokens(e.question()); // search() inside ask()

			List<Ret> rets = retrieved.stream()
					.map(rc -> new Ret(rc.chunk().provenance().filename(),
							rc.chunk().provenance().pageNumber(), rc.score(), rc.chunk().text()))
					.toList();
			String context = numberedContext(rets);
			genInputTokens += estimator.estimateChatTokens(context)
					+ estimator.estimateChatTokens(e.question()) + CostModel.GEN_SCAFFOLD_TOKENS;
			genOutputTokens += estimator.estimateChatTokens(answer.text());
			genCalls++;

			frozen.add(new Frozen(e.id(), e.type(), e.question(), e.isShouldReturnNothing(),
					e.relevantPages(), rets, answer.text()));
		}
		return frozen;
	}

	private List<Frozen> loadFrozen(Path path) {
		try {
			DualJudgeDoc doc = MAPPER.readValue(Files.readString(path), DualJudgeDoc.class);
			return doc.frozen();
		}
		catch (IOException e) {
			throw new UncheckedIOException("could not read frozen answers from " + path, e);
		}
	}

	/** Numbered, 1-based context block of FULL chunk text — identical to what the judge saw. */
	private static String numberedContext(List<Ret> chunks) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			if (i > 0) {
				sb.append("\n\n");
			}
			sb.append('[').append(i + 1).append("] ").append(chunks.get(i).text());
		}
		return sb.toString();
	}

	private static String abstentionVerdict(AbstentionJudgement j) {
		return !j.parsed() ? "UNPARSEABLE" : j.abstained() ? "ABSTAIN-OK" : "FAIL-answered";
	}

	// --- cost -----------------------------------------------------------------------

	private List<String> costLines(long aIn, long aOut, long bIn, long bOut) {
		double embed = embedTokens / 1_000_000.0 * CostModel.EMBED_USD_PER_M;
		Double gen = CostModel.chatUsd(CostModel.GEN_MODEL, genInputTokens, genOutputTokens);
		Double aUsd = CostModel.chatUsd(judgeAModel, aIn, aOut);
		Double bUsd = CostModel.chatUsd(judgeBModel, bIn, bOut);

		List<String> out = new ArrayList<>();
		out.add(" MEASURED/ESTIMATED COST (dual-judge A/B pass)");
		if (reuseFrozen) {
			out.add("   generation + embeddings: $0 (answers reused from frozen file)");
		}
		else {
			out.add(String.format("   embeddings (est):        %s", usd(embed)));
			out.add(String.format("   generation %-12s (est):  %s   %d calls",
					"(" + CostModel.GEN_MODEL + ")", usd(gen), genCalls));
		}
		out.add(String.format("   judge A %-14s [measured]: %s   in=%d out=%d",
				"(" + judgeAModel + ")", usd(aUsd), aIn, aOut));
		out.add(String.format("   judge B %-14s [measured]: %s   in=%d out=%d",
				"(" + judgeBModel + ")", usd(bUsd), bIn, bOut));
		double total = (reuseFrozen ? 0.0 : embed + orZero(gen)) + orZero(aUsd) + orZero(bUsd);
		out.add(String.format("   TOTAL: %s", usd(total)));
		return out;
	}

	private static double orZero(Double d) {
		return d == null ? 0.0 : d;
	}

	private static String usd(Double amount) {
		return amount == null ? "n/a" : String.format("$%.4f", amount);
	}

	// --- printing / persistence -----------------------------------------------------

	private void printHeader(String stamp, int n) {
		System.out.println();
		System.out.println("==================================================================");
		System.out.println(" DocuMind dual-judge A/B — identical frozen answers, two judges");
		System.out.println(" " + stamp + "   generation=" + generationModel
				+ "   judgeA=" + judgeAModel + "   judgeB=" + judgeBModel);
		System.out.println(" answers: " + (reuseFrozen ? "REUSED from " + frozenAnswersPath : "freshly generated & frozen")
				+ "   entries=" + n);
		System.out.println("==================================================================");
	}

	private void printComparisonSummary(List<Comparison> comparisons, int agreements, List<String> costLines) {
		System.out.println("------------------------------------------------------------------");
		List<Comparison> disagreements = comparisons.stream().filter(c -> !c.agree()).toList();
		System.out.printf(" AGREEMENT: %d/%d (%.1f%%)   disagreements: %d%n",
				agreements, comparisons.size(),
				comparisons.isEmpty() ? 0.0 : 100.0 * agreements / comparisons.size(),
				disagreements.size());
		if (disagreements.isEmpty()) {
			System.out.println("   the two judges agree on every entry — no judge-only disagreement.");
		}
		else {
			disagreements.forEach(c -> {
				System.out.println("   ! " + c.id() + " (" + c.type() + "): "
						+ judgeAModel + "=" + c.verdictA() + "  vs  " + judgeBModel + "=" + c.verdictB());
				System.out.println("       A: " + c.rationaleA());
				System.out.println("       B: " + c.rationaleB());
			});
		}
		System.out.println("------------------------------------------------------------------");
		costLines.forEach(System.out::println);
	}

	private void writeJson(Path file, DualJudgeDoc doc) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
		}
		catch (IOException e) {
			throw new UncheckedIOException("could not write dual-judge JSON to " + file, e);
		}
	}

	// --- persisted shapes -----------------------------------------------------------

	/** One retrieved chunk, with its full text so the context is reproducible. */
	record Ret(String filename, int page, double score, String text) {
	}

	/** A frozen answer: question, the retrieved context it was grounded in, and the answer. */
	record Frozen(String id, String type, String question, boolean srn,
			List<Integer> expectedPages, List<Ret> retrieved, String answer) {
	}

	/** Both judges' verdicts on one frozen answer. */
	record Comparison(String id, String type, boolean srn, String verdictA, String rationaleA,
			String verdictB, String rationaleB, boolean agree) {
	}

	/** The full reproducible artifact written to disk. */
	record DualJudgeDoc(String timestamp, String generationModel, String judgeA, String judgeB,
			int topK, boolean reusedFrozen, List<Frozen> frozen, List<Comparison> comparisons,
			List<String> cost) {
	}
}
