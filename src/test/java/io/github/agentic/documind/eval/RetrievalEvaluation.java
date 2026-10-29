package io.github.agentic.documind.eval;

import io.github.agentic.documind.TestcontainersConfiguration;
import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.DocumentChunker;
import io.github.agentic.documind.domain.Chunk;
import io.github.agentic.documind.domain.RetrievedChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-2 retrieval evaluation harness — <b>Step 1: hit-rate only</b>.
 *
 * <p>Scores the live retrieval path ({@link ChunkStore#search}) against the
 * version-controlled golden set and prints a report. This step makes <em>embedding</em>
 * calls (ingesting the corpus once, then one query embedding per question) but <b>no
 * chat or judge calls</b> — answer faithfulness and the cost summary are later steps.
 *
 * <p>Tagged {@code eval} so it is excluded from {@code mvn test} (see the Surefire
 * config in the pom); run on demand with {@code ./mvnw test -Peval}. Needs
 * {@code OPENAI_API_KEY} in the environment and a running Docker daemon (Testcontainers
 * Elasticsearch).
 *
 * <p>A retrieval hit requires BOTH the correct source file and a page in the entry's
 * {@code relevantPages}. should-return-nothing entries are reported as their own
 * category; their abstention metric needs a generated answer, so it arrives with the
 * faithfulness step — here they only show what retrieval returned.
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class RetrievalEvaluation {

	@Autowired
	private DocumentChunker chunker;

	@Autowired
	private ChunkStore chunkStore;

	@Value("${documind.retrieval.top-k:4}")
	private int topK;

	private List<GoldenEntry> golden;

	/** filename -> highest page number ingested, for the impossible-page sanity check. */
	private final Map<String, Integer> maxPageByFile = new TreeMap<>();

	/** source files actually present and ingested this run. */
	private final List<String> ingestedFiles = new ArrayList<>();

	@BeforeAll
	void ingestCorpusOnce() {
		golden = GoldenSet.load();

		// Ingest each distinct source file ONCE (embeds the corpus a single time). We inline
		// IngestionService's chunk -> save using the same chunker and store beans the app
		// uses, so the harness can also read each chunk's page for the sanity check below.
		golden.stream().map(GoldenEntry::filename).distinct().forEach(filename -> {
			ClassPathResource pdf = new ClassPathResource("eval/corpus/" + filename);
			if (!pdf.exists()) {
				return; // flagged later; e.g. the résumé is intentionally not committed
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
			int maxPage = chunks.stream().mapToInt(c -> c.provenance().pageNumber()).max().orElse(0);
			maxPageByFile.put(filename, maxPage);
		});

		assertThat(ingestedFiles).as("at least the public Huberman corpus must ingest")
				.contains("huberman_sleep.pdf");
	}

	@Test
	void retrievalHitRate() {
		Report report = new Report();
		printHeader();
		printFlags();

		for (GoldenEntry e : golden) {
			List<RetrievedChunk> retrieved = chunkStore.search(e.question(), topK);

			boolean fileMissing = !ingestedFiles.contains(e.filename());
			if (fileMissing) {
				printQuestionLine(e, retrieved, "SKIP", "source file not ingested");
				report.skipped++;
				continue;
			}

			if (e.isShouldReturnNothing()) {
				// Pure-retrieval step: we only display what came back. Whether the pipeline
				// correctly ABSTAINS is an answer-level question, scored in the judge step.
				printQuestionLine(e, retrieved, "n/a", "should-return-nothing (abstention scored later)");
				report.srnSeen++;
				continue;
			}

			boolean hit1 = !retrieved.isEmpty() && matches(e, retrieved.get(0));
			boolean hitK = retrieved.stream().anyMatch(rc -> matches(e, rc));
			report.record(e.type(), hit1, hitK);
			printQuestionLine(e, retrieved, (hit1 ? "H@1 " : "    ") + (hitK ? "H@k" : "miss"), "");
		}

		report.printSummary(topK);
	}

	/** A hit requires BOTH the correct file AND a relevant page (see {@link GoldenEntry#isHit}). */
	private boolean matches(GoldenEntry e, RetrievedChunk rc) {
		return e.isHit(rc.chunk().provenance().filename(), rc.chunk().provenance().pageNumber());
	}

	// --- printing -----------------------------------------------------------------

	private void printHeader() {
		System.out.println();
		System.out.println("==================================================================");
		System.out.println(" DocuMind retrieval eval — hit-rate (Step 1, no judge calls)");
		System.out.println(" top-k = " + topK + "   entries = " + golden.size()
				+ "   ingested = " + ingestedFiles);
		System.out.println(" page counts: " + maxPageByFile);
		System.out.println("==================================================================");
	}

	private void printFlags() {
		List<String> flags = new ArrayList<>(GoldenSet.structuralWarnings(golden));
		for (GoldenEntry e : golden) {
			if (!ingestedFiles.contains(e.filename())) {
				flags.add(e.id() + ": source file '" + e.filename() + "' not ingested -> entry skipped");
				continue;
			}
			int max = maxPageByFile.getOrDefault(e.filename(), 0);
			for (Integer p : e.relevantPages()) {
				if (p != null && p > max) {
					flags.add(e.id() + ": relevantPage " + p + " exceeds '" + e.filename()
							+ "' page count (" + max + ") — impossible page");
				}
			}
		}
		if (flags.isEmpty()) {
			System.out.println(" golden-set flags: none");
		}
		else {
			System.out.println(" golden-set flags (" + flags.size() + "):");
			flags.forEach(f -> System.out.println("   ! " + f));
		}
		System.out.println("------------------------------------------------------------------");
		System.out.printf(" %-5s %-13s %-9s %-10s %s%n", "id", "type", "verdict", "expected", "retrieved [file p# (score)]");
	}

	private void printQuestionLine(GoldenEntry e, List<RetrievedChunk> retrieved, String verdict, String note) {
		StringBuilder got = new StringBuilder();
		for (RetrievedChunk rc : retrieved) {
			got.append(abbrev(rc.chunk().provenance().filename()))
					.append(" p").append(rc.chunk().provenance().pageNumber())
					.append(String.format("(%.3f)", rc.score())).append(" ");
		}
		String expected = e.isShouldReturnNothing() ? "(none)"
				: abbrev(e.filename()) + " " + e.relevantPages();
		System.out.printf(" %-5s %-13s %-9s %-10s %s%n",
				e.id(), e.type(), verdict, expected, got.toString().strip());
		if (!note.isEmpty()) {
			System.out.println("        note: " + note);
		}
	}

	/** Short tag so the retrieved column stays readable. */
	private static String abbrev(String filename) {
		if (filename.startsWith("huberman")) {
			return "HUB";
		}
		if (filename.startsWith("Rajeet")) {
			return "RES";
		}
		return filename;
	}

	/** Accumulates hit counts overall and per type. */
	private static final class Report {

		int skipped;
		int srnSeen;
		final Map<String, int[]> byType = new LinkedHashMap<>(); // type -> {n, hit1, hitK}
		final int[] overall = new int[3];

		void record(String type, boolean hit1, boolean hitK) {
			int[] t = byType.computeIfAbsent(type, k -> new int[3]);
			t[0]++;
			overall[0]++;
			if (hit1) {
				t[1]++;
				overall[1]++;
			}
			if (hitK) {
				t[2]++;
				overall[2]++;
			}
		}

		void printSummary(int topK) {
			System.out.println("------------------------------------------------------------------");
			System.out.println(" SUMMARY (scored: factual + paraphrased; should-return-nothing excluded)");
			System.out.printf("   %-14s %6s %12s %12s%n", "category", "n", "hit-rate@1", "hit-rate@" + topK);
			byType.forEach((type, t) -> System.out.printf("   %-14s %6d %12s %12s%n",
					type, t[0], pct(t[1], t[0]), pct(t[2], t[0])));
			System.out.printf("   %-14s %6d %12s %12s%n", "OVERALL",
					overall[0], pct(overall[1], overall[0]), pct(overall[2], overall[0]));
			System.out.println();
			System.out.println("   should-return-nothing entries seen: " + srnSeen
					+ "  (abstention rate scored in the faithfulness step)");
			System.out.println("   skipped (source file absent): " + skipped);
			System.out.println("==================================================================");
		}

		private static String pct(int num, int den) {
			if (den == 0) {
				return "n/a";
			}
			return String.format("%.1f%% (%d/%d)", 100.0 * num / den, num, den);
		}
	}
}
