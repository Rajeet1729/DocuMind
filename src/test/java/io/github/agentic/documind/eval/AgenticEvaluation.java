package io.github.agentic.documind.eval;

import io.github.agentic.documind.TestcontainersConfiguration;
import io.github.agentic.documind.adapter.out.llm.ChatClientReasoningModel;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.DocumentChunker;
import io.github.agentic.documind.application.port.out.ReasoningModel;
import io.github.agentic.documind.application.service.AgenticRetrieval;
import io.github.agentic.documind.application.service.QuestionAnsweringService;
import io.github.agentic.documind.application.service.RetrievalStrategy;
import io.github.agentic.documind.application.service.SingleShotRetrieval;
import io.github.agentic.documind.domain.Answer;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.RetrievedChunk;
import io.github.agentic.documind.eval.AgenticReport.Block;
import io.github.agentic.documind.eval.AgenticReport.Outcome;
import io.github.agentic.documind.eval.Judge.AbstentionJudgement;
import io.github.agentic.documind.eval.Judge.FaithfulnessJudgement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-4 harness — <b>single-shot vs agentic retrieval</b>, budget-controlled, multi-run.
 *
 * <p>For each {@code k} in {@code documind.eval.agentic.k-values} it runs the real
 * {@link QuestionAnsweringService} pipeline over the golden set with two strategies:
 * {@link SingleShotRetrieval} once ({@code SS@k}; deterministic retrieval) and
 * {@link AgenticRetrieval} {@code documind.eval.agentic.runs} times ({@code AG@k}; the
 * gate and reformulation are stochastic, so stability is measured, not assumed). Both
 * arms are built here, side by side over the same ingested index, so the comparison is
 * paired per question; the production flag only chooses which bean the app wires.
 *
 * <p>Why the harness does not re-derive the context with a second {@code search()} (as the
 * Slice-2 harness does): agentic retrieval is non-deterministic, so a second call could
 * return a different context than the one the answer was generated from. Instead the
 * strategy is wrapped in a recorder and the judge sees <b>exactly</b> the context
 * {@code ask()} used. Reasoning calls go through a {@code ChatClient} with a metering
 * advisor, so their token usage is the API's, not an estimate.
 *
 * <p>Reliability caveats this harness cannot remove — reported, not hidden: small N (25
 * answerable); the rescue ceiling at a given {@code k} is only the questions {@code SS@k}
 * misses; generation is non-deterministic, so answer-level metrics are observational.
 *
 * <p>Run: {@code ./mvnw test -Peval -Dtest=AgenticEvaluation -Ddocumind.retrieval.hybrid.enabled=true}.
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class AgenticEvaluation {

	@Autowired
	private DocumentChunker chunker;

	@Autowired
	private ChunkStore chunkStore;

	/** The application's generation client — both arms answer with it, unchanged. */
	@Autowired
	private ChatClient chatClient;

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Value("${documind.eval.agentic.k-values:2,4}")
	private String kValuesProperty;

	@Value("${documind.eval.agentic.runs:3}")
	private int runs;

	@Value("${documind.retrieval.agentic.max-iterations:2}")
	private int maxIterations;

	@Value("${documind.eval.judge-model:gpt-6-luna}")
	private String judgeModel;

	@Value("${spring.ai.openai.chat.model:unknown}")
	private String generationModel;

	@Value("${documind.retrieval.hybrid.enabled:false}")
	private boolean hybridEnabled;

	@Value("${documind.retrieval.hybrid.candidate-k:20}")
	private int candidateK;

	@Value("${documind.retrieval.hybrid.rrf-k:60}")
	private int rrfK;

	private List<GoldenEntry> golden;
	private final List<String> ingestedFiles = new ArrayList<>();
	private final Map<String, Integer> chunkCountByFile = new LinkedHashMap<>();
	private CostModel cost;

	@BeforeAll
	void ingestCorpusOnce() {
		// The single-shot arm IS the Slice-3 baseline, and the loop reuses it as its primitive.
		assertThat(hybridEnabled).as("run with -Ddocumind.retrieval.hybrid.enabled=true: the single-shot "
				+ "arm is the Slice-3 hybrid baseline").isTrue();
		cost = new CostModel(judgeModel);

		List<GoldenEntry> all = GoldenSet.load();
		all.stream().map(GoldenEntry::filename).distinct().forEach(filename -> {
			ClassPathResource pdf = new ClassPathResource("eval/corpus/" + filename);
			if (!pdf.exists()) {
				return; // e.g. the résumé is intentionally not committed; its entries are dropped below
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
			chunkCountByFile.put(filename, chunks.size());
			chunks.forEach(c -> cost.addCorpusEmbedding(c.text()));
		});
		golden = all.stream().filter(e -> ingestedFiles.contains(e.filename())).toList();

		assertThat(ingestedFiles).as("at least the public Huberman corpus must ingest")
				.contains("huberman_sleep.pdf");
	}

	@Test
	void agenticVersusSingleShot() {
		int[] kValues = Arrays.stream(kValuesProperty.split(",")).map(String::strip).mapToInt(Integer::parseInt).toArray();
		int depth = Arrays.stream(kValues).max().orElseThrow() * maxIterations;

		Judge judge = new Judge(chatClientBuilder, judgeModel);
		UsageMeter meter = new UsageMeter();
		ReasoningModel reasoning = new ChatClientReasoningModel(chatClientBuilder.clone().defaultAdvisors(meter).build());
		AgenticReport report = new AgenticReport(cost);

		// The original question's single-shot ranking, deep enough for every SS@C control.
		// Hybrid fuses a fixed candidate depth then truncates, so SS@n is exactly a prefix of this.
		Map<String, List<RetrievedChunk>> ranking = new LinkedHashMap<>();
		for (GoldenEntry e : golden) {
			ranking.put(e.id(), chunkStore.search(e.question(), depth));
			cost.addQueryEmbedding(e.question());
		}

		List<String> summary = new ArrayList<>(headerLines(kValues));
		List<String> details = new ArrayList<>();
		for (int k : kValues) {
			Block block = new Block(k, maxIterations, ranking);
			block.singleShot = runArm("SS@" + k, new SingleShotRetrieval(chunkStore), k, meter, judge);
			for (int r = 1; r <= runs; r++) {
				block.agentic.add(runArm("AG@" + k + " r" + r,
						new AgenticRetrieval(chunkStore, reasoning, maxIterations), k, meter, judge));
			}
			List<String> lines = report.summary(block);
			lines.forEach(System.out::println);
			summary.addAll(lines);
			summary.add("");
			details.addAll(report.details(block));
		}

		System.out.println("------------------------------------------------------------------");
		System.out.println(" WHOLE-RUN SPEND (all arms, harness rankings, judge)");
		cost.printSummary();

		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
		Path file = Path.of("target", "eval", "agentic-" + stamp + ".txt");
		List<String> all = new ArrayList<>(summary);
		all.add(" WHOLE-RUN SPEND (all arms, harness rankings, judge)");
		all.addAll(cost.summaryLines());
		all.add("");
		all.addAll(details);
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, all);
		}
		catch (IOException e) {
			throw new UncheckedIOException("could not write agentic report to " + file, e);
		}
		System.out.println(" persisted results: " + file.toAbsolutePath());
		System.out.println("==================================================================");
	}

	/** Run every golden question through the real pipeline with one strategy; judge each answer. */
	private List<Outcome> runArm(String label, RetrievalStrategy strategy, int k, UsageMeter meter, Judge judge) {
		Recording recording = new Recording(strategy);
		QuestionAnsweringService pipeline = new QuestionAnsweringService(recording, chatClient, k);
		List<Outcome> outcomes = new ArrayList<>();
		for (GoldenEntry e : golden) {
			meter.reset();
			long start = System.nanoTime();
			Answer answer = pipeline.ask(e.question());
			long latencyMs = (System.nanoTime() - start) / 1_000_000;
			RetrievalStrategy.Result used = recording.last();
			int[] usage = meter.reset(); // {calls, promptTokens, completionTokens}

			used.trace().queries().forEach(cost::addQueryEmbedding);
			cost.addReasoning(usage[0], usage[1], usage[2]);
			String context = numberedContext(used.context());
			int genIn = 0;
			int genOut = 0;
			if (!used.context().isEmpty()) {
				genIn = cost.chatTokens(context) + cost.chatTokens(e.question()) + CostModel.GEN_SCAFFOLD_TOKENS;
				genOut = cost.chatTokens(answer.text());
				cost.addGeneration(genIn - CostModel.GEN_SCAFFOLD_TOKENS, answer.text());
			}

			String verdict;
			String rationale;
			if (e.isShouldReturnNothing()) {
				AbstentionJudgement j = judge.judgeAbstention(e.question(), answer.text());
				cost.addJudge(j.promptTokens(), j.completionTokens());
				verdict = !j.parsed() ? "UNPARSEABLE" : j.abstained() ? "ABSTAIN-OK" : "FAIL-answered";
				rationale = j.rationale();
			}
			else {
				FaithfulnessJudgement j = judge.judgeFaithfulness(e.question(), context, answer.text());
				cost.addJudge(j.promptTokens(), j.completionTokens());
				verdict = j.grade().name();
				rationale = j.rationale();
			}

			Outcome o = new Outcome(e, used.context(), used.trace(), usage[0], usage[1], usage[2],
					genIn, genOut, latencyMs, verdict, rationale, answer.text());
			outcomes.add(o);
			System.out.printf(" %-10s %-5s ctx=%-24s stop=%-19s calls=%d %s%n", label, e.id(),
					o.context().stream().map(AgenticReport::label).reduce((a, b) -> a + " " + b).orElse("(none)"),
					used.trace().stopReason(), usage[0], verdict);
		}
		return outcomes;
	}

	private List<String> headerLines(int[] kValues) {
		long srn = golden.stream().filter(GoldenEntry::isShouldReturnNothing).count();
		List<String> out = new ArrayList<>();
		out.add("==================================================================");
		out.add(" DocuMind agentic eval — single-shot vs agentic retrieval (Slice 4, PAID)");
		out.add(" timestamp(UTC) = " + Instant.now());
		out.add(" retrieval primitive = hybrid(BM25+vector RRF, candidateK=" + candidateK + ", rrfK=" + rrfK + ")"
				+ "   chunks/doc = " + chunkCountByFile);
		out.add(" k = " + Arrays.toString(kValues) + "   max-iterations = " + maxIterations + "   agentic runs = " + runs);
		out.add(" generation = reasoning = " + generationModel + " (app ChatClient; default temperature — the model rejects 0)"
				+ "   judge = " + judgeModel);
		out.add(" entries = " + golden.size() + " (answerable " + (golden.size() - srn) + ", should-return-nothing " + srn
				+ ")   ingested = " + ingestedFiles);
		out.add("==================================================================");
		out.add(" GATE SYSTEM PROMPT (verbatim):");
		out.add(indent(ChatClientReasoningModel.SUFFICIENCY_SYSTEM_PROMPT));
		out.add(" REFORMULATION SYSTEM PROMPT (verbatim):");
		out.add(indent(ChatClientReasoningModel.REFORMULATION_SYSTEM_PROMPT));
		out.add("");
		return out;
	}

	/** Numbered, 1-based context block of FULL chunk text — the generator's and the judge's view. */
	private static String numberedContext(List<RetrievedChunk> chunks) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			if (i > 0) {
				sb.append("\n\n");
			}
			sb.append('[').append(i + 1).append("] ").append(chunks.get(i).chunk().text());
		}
		return sb.toString();
	}

	private static String indent(String block) {
		return "   | " + block.replace("\n", "\n   | ");
	}

	/** Delegates to a strategy and keeps its last result, so the harness sees the exact context {@code ask()} used. */
	private static final class Recording implements RetrievalStrategy {

		private final RetrievalStrategy delegate;
		private Result last;

		Recording(RetrievalStrategy delegate) {
			this.delegate = delegate;
		}

		@Override
		public Result retrieve(String question, int topK) {
			last = delegate.retrieve(question, topK);
			return last;
		}

		Result last() {
			return last;
		}
	}

	/** Advisor that tallies the API-reported usage of every call through its client. */
	private static final class UsageMeter implements CallAdvisor {

		private int calls;
		private int promptTokens;
		private int completionTokens;

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			ChatClientResponse response = chain.nextCall(request);
			Usage usage = response.chatResponse() == null ? null : response.chatResponse().getMetadata().getUsage();
			calls++;
			promptTokens += usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
			completionTokens += usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
			return response;
		}

		/** @return {calls, promptTokens, completionTokens} since the last reset */
		int[] reset() {
			int[] snapshot = {calls, promptTokens, completionTokens};
			calls = 0;
			promptTokens = 0;
			completionTokens = 0;
			return snapshot;
		}

		@Override
		public String getName() {
			return "eval-usage-meter";
		}

		@Override
		public int getOrder() {
			return 0; // outermost, ahead of the terminal model-call advisor
		}
	}
}
