package io.github.agentic.documind.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the per-question results of a full eval run and writes them to a
 * <b>diff-friendly</b> plain-text file under {@code target/eval/}.
 *
 * <p>The format is designed so that a plain {@code diff} of two run files is readable and
 * the run-over-run story is obvious — e.g. after Slice 3 adds reranking, "hit@1 went
 * 76% → X%" should jump out of a diff. To that end: questions are emitted in a stable
 * order (by id: alpha prefix then numeric suffix, so {@code s1..s20} then no surprise
 * {@code s1,s10,s11}), every question uses the same fixed field layout, and the summary
 * block (retrieval hit-rate, faithfulness, abstention, cost) sits at the top where it
 * diffs cleanly. Generated answers are non-deterministic and so will differ between runs;
 * that shows up as a localised per-question change, not churn across the file.
 */
final class RunReport {

	/** One retrieved chunk, rendered {@code file:page(score)} in rank order. */
	record Retrieved(String filename, int page, double score) {
		String label() {
			return filename + ":" + page + String.format("(%.3f)", score);
		}
	}

	/**
	 * Everything recorded for one golden question. {@code hit1}/{@code hitK} are null for
	 * should-return-nothing and skipped entries; {@code verdict} is the faithfulness grade,
	 * an abstention outcome ({@code ABSTAIN-OK}/{@code FAIL-answered}), or {@code SKIP}.
	 */
	record QResult(String id, String type, String filename, String question, String answer,
			List<Retrieved> retrieved, Boolean hit1, Boolean hitK,
			List<Integer> expectedPages, String verdict, String rationale, boolean srn) {
	}

	private static final Pattern ID_PARTS = Pattern.compile("^([^0-9]*)(\\d*)$");

	private final List<QResult> results = new ArrayList<>();

	void add(QResult r) {
		results.add(r);
	}

	/** Stable id order: alpha prefix, then numeric suffix as a number (not lexically). */
	private List<QResult> sorted() {
		List<QResult> copy = new ArrayList<>(results);
		copy.sort(Comparator
				.comparing((QResult r) -> idPrefix(r.id()))
				.thenComparingInt(r -> idNumber(r.id()))
				.thenComparing(QResult::id));
		return copy;
	}

	private static String idPrefix(String id) {
		Matcher m = ID_PARTS.matcher(id);
		return m.matches() ? m.group(1) : id;
	}

	private static int idNumber(String id) {
		Matcher m = ID_PARTS.matcher(id);
		if (m.matches() && !m.group(2).isEmpty()) {
			return Integer.parseInt(m.group(2));
		}
		return -1;
	}

	/** Retrieval + faithfulness + abstention summary, derived from the collected results. */
	List<String> summaryLines() {
		List<String> out = new ArrayList<>();

		// --- retrieval hit-rate (scored entries only) ---
		out.add(" RETRIEVAL hit-rate (file+page match; scored: factual + paraphrased)");
		Map<String, int[]> retr = new LinkedHashMap<>(); // type -> {n, hit1, hitK}
		int[] retrAll = new int[3];
		for (QResult r : results) {
			if (r.hit1() == null) {
				continue;
			}
			int[] t = retr.computeIfAbsent(r.type(), k -> new int[3]);
			t[0]++;
			retrAll[0]++;
			if (r.hit1()) {
				t[1]++;
				retrAll[1]++;
			}
			if (r.hitK()) {
				t[2]++;
				retrAll[2]++;
			}
		}
		out.add(String.format("   %-14s %5s %14s %14s", "category", "n", "hit@1", "hit@k"));
		retr.forEach((type, t) -> out.add(String.format("   %-14s %5d %14s %14s",
				type, t[0], pct(t[1], t[0]), pct(t[2], t[0]))));
		out.add(String.format("   %-14s %5d %14s %14s", "OVERALL", retrAll[0],
				pct(retrAll[1], retrAll[0]), pct(retrAll[2], retrAll[0])));
		out.add("");

		// --- retrieval hit-rate by document (makes a per-corpus divergence visible) ---
		out.add(" RETRIEVAL hit-rate by document");
		Map<String, int[]> byDoc = new LinkedHashMap<>();
		for (QResult r : results) {
			if (r.hit1() == null) {
				continue;
			}
			int[] d = byDoc.computeIfAbsent(r.filename(), k -> new int[3]);
			d[0]++;
			if (r.hit1()) {
				d[1]++;
			}
			if (r.hitK()) {
				d[2]++;
			}
		}
		out.add(String.format("   %-30s %5s %14s %14s", "document", "n", "hit@1", "hit@k"));
		byDoc.forEach((doc, d) -> out.add(String.format("   %-30s %5d %14s %14s",
				doc, d[0], pct(d[1], d[0]), pct(d[2], d[0]))));
		out.add("");

		// --- faithfulness (headline = share SUPPORTED) ---
		out.add(" FAITHFULNESS (headline = share SUPPORTED; PARTIAL separate)");
		Map<String, Map<String, Integer>> faith = new LinkedHashMap<>();
		Map<String, Integer> faithAll = new LinkedHashMap<>();
		for (QResult r : results) {
			if (r.srn() || "SKIP".equals(r.verdict())) {
				continue;
			}
			faith.computeIfAbsent(r.type(), k -> new LinkedHashMap<>()).merge(r.verdict(), 1, Integer::sum);
			faithAll.merge(r.verdict(), 1, Integer::sum);
		}
		out.add(String.format("   %-14s %5s %14s %9s %12s %12s",
				"category", "n", "SUPPORTED", "PARTIAL", "UNSUPPORTED", "unparseable"));
		faith.forEach((type, counts) -> out.add(faithRow(type, counts)));
		out.add(faithRow("OVERALL", faithAll));
		out.add("");

		// --- abstention ---
		long srnTotal = results.stream().filter(r -> r.srn() && !"SKIP".equals(r.verdict())).count();
		long srnOk = results.stream().filter(r -> "ABSTAIN-OK".equals(r.verdict())).count();
		out.add(" ABSTENTION (should-return-nothing; its own metric)");
		out.add("   correctly abstained: " + pct((int) srnOk, (int) srnTotal));

		long skipped = results.stream().filter(r -> "SKIP".equals(r.verdict())).count();
		if (skipped > 0) {
			out.add("   skipped (source file absent): " + skipped);
		}
		return out;
	}

	private static String faithRow(String label, Map<String, Integer> counts) {
		int s = counts.getOrDefault("SUPPORTED", 0);
		int p = counts.getOrDefault("PARTIAL", 0);
		int u = counts.getOrDefault("UNSUPPORTED", 0);
		int x = counts.getOrDefault("UNPARSEABLE", 0);
		int n = s + p + u + x;
		return String.format("   %-14s %5d %14s %9d %12d %12d", label, n, pct(s, n), p, u, x);
	}

	private static String pct(int num, int den) {
		return den == 0 ? "n/a" : String.format("%.1f%% (%d/%d)", 100.0 * num / den, num, den);
	}

	/**
	 * Write the full report. {@code headerLines} carry run metadata (models, timestamp,
	 * corpus); {@code costLines} are {@link CostModel#summaryLines()} verbatim.
	 */
	void write(Path file, List<String> headerLines, List<String> costLines) {
		StringBuilder sb = new StringBuilder();
		sb.append("# DocuMind eval run\n");
		headerLines.forEach(h -> sb.append("# ").append(h).append('\n'));
		sb.append("#\n");
		sb.append("# ===================== SUMMARY =====================\n");
		summaryLines().forEach(l -> sb.append('#').append(l).append('\n'));
		sb.append("#\n");
		costLines.forEach(l -> sb.append('#').append(l).append('\n'));
		sb.append("#\n");
		sb.append("# ============== PER QUESTION (sorted by id) ==============\n\n");

		for (QResult r : sorted()) {
			sb.append('[').append(r.id()).append("] type=").append(r.type()).append('\n');
			sb.append("  question : ").append(oneLine(r.question())).append('\n');
			sb.append("  expected : ").append(r.srn() ? "(should-return-nothing)" : "pages " + r.expectedPages()).append('\n');
			sb.append("  retrieved: ").append(retrievedLabels(r.retrieved())).append('\n');
			sb.append("  hit@1=").append(flag(r.hit1())).append("  hit@k=").append(flag(r.hitK())).append('\n');
			sb.append("  verdict  : ").append(r.verdict()).append('\n');
			sb.append("  rationale: ").append(oneLine(r.rationale())).append('\n');
			sb.append("  answer   : |\n").append(indentBlock(r.answer())).append('\n');
			sb.append('\n');
		}

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, sb.toString());
		}
		catch (IOException e) {
			throw new UncheckedIOException("could not write eval report to " + file, e);
		}
	}

	private static String retrievedLabels(List<Retrieved> retrieved) {
		if (retrieved == null || retrieved.isEmpty()) {
			return "(none)";
		}
		StringBuilder sb = new StringBuilder();
		for (Retrieved rc : retrieved) {
			sb.append(rc.label()).append(' ');
		}
		return sb.toString().strip();
	}

	private static String flag(Boolean b) {
		return b == null ? "n/a" : b.toString();
	}

	private static String oneLine(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").strip();
	}

	private static String indentBlock(String text) {
		String t = text == null ? "" : text.strip();
		String[] lines = t.split("\n", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				sb.append('\n');
			}
			sb.append("    ").append(lines[i]);
		}
		return sb.toString();
	}
}
