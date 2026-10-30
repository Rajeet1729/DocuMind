package io.github.agentic.documind.eval;

import io.github.agentic.documind.application.port.out.ReasoningModel.Sufficiency;
import io.github.agentic.documind.application.port.out.ReasoningModel.SufficiencyVerdict;
import io.github.agentic.documind.application.service.RetrievalStrategy.Trace;
import io.github.agentic.documind.domain.RetrievedChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Derives the Slice-4 agentic-vs-single-shot report from one list of per-question
 * {@link Outcome}s per arm-run (the derive-summaries-from-one-list rule).
 *
 * <p>The comparison is budget-controlled on two axes:
 * <ul>
 *   <li><b>Final (generation) budget</b> — the loop's context is capped to {@code k}, so
 *       {@code SS@k} (single-shot at the same {@code k}) is the equal-final-budget control
 *       and rescued/broken are counted against it.</li>
 *   <li><b>Candidate budget</b> — the loop examined {@code C} distinct chunks to pick its
 *       {@code k}. {@code SS@C} (single-shot retrieving {@code C} chunks with the original
 *       question, read from the deterministic ranking) is the control for "the loop just
 *       looked at more chunks". A rescue {@code SS@C} also gets is <b>re-retrieval</b>; one
 *       it misses is <b>reformulation-only</b> — the only "added intelligence" signal.</li>
 * </ul>
 * Retrieval outcomes are hit@k on the context actually sent to generation; answer-level
 * faithfulness and abstention are observational (generation is non-deterministic).
 */
final class AgenticReport {

	/** Slice-3 residual hit@1 misses — tagged so a rescue of one is never misread as a loop success. */
	static final Set<String> SLICE3_RESIDUALS = Set.of("s7", "s8", "s15");

	/**
	 * One question in one arm-run. {@code meteredCalls}/{@code reasoningIn}/{@code reasoningOut}
	 * are the API-reported reasoning usage; {@code genIn}/{@code genOut} are jtokkit estimates.
	 */
	record Outcome(GoldenEntry entry, List<RetrievedChunk> context, Trace trace, int meteredCalls,
			int reasoningIn, int reasoningOut, int genIn, int genOut, long latencyMs,
			String verdict, String rationale, String answer) {

		/** hit@k on the final context; null for should-return-nothing. */
		Boolean hitK() {
			return entry.isShouldReturnNothing() ? null : anyHit(entry, context);
		}

		/** hit@1 on the final context; null for should-return-nothing. */
		Boolean hit1() {
			return entry.isShouldReturnNothing() ? null : !context.isEmpty() && isHit(entry, context.get(0));
		}

		boolean gateInsufficient() {
			return trace.verdicts().stream().anyMatch(v -> v.sufficiency() == Sufficiency.INSUFFICIENT);
		}

		boolean gateUnparseable() {
			return trace.verdicts().stream().anyMatch(v -> v.sufficiency() == Sufficiency.UNPARSEABLE);
		}
	}

	/** Everything measured at one top-k: the deterministic ranking, the single-shot arm, the agentic runs. */
	static final class Block {

		final int k;
		final int maxIterations;
		/** question id → the original question's single-shot ranking, to depth k × maxIterations. */
		final Map<String, List<RetrievedChunk>> ranking;
		List<Outcome> singleShot;
		final List<List<Outcome>> agentic = new ArrayList<>();

		Block(int k, int maxIterations, Map<String, List<RetrievedChunk>> ranking) {
			this.k = k;
			this.maxIterations = maxIterations;
			this.ranking = ranking;
		}

		List<RetrievedChunk> prefix(String id, int n) {
			List<RetrievedChunk> r = ranking.get(id);
			return r.subList(0, Math.min(n, r.size()));
		}

		/** SS@C hit: single-shot with the original question at the loop's candidate budget. */
		boolean ssAtCandidatesHit(Outcome ag) {
			return anyHit(ag.entry(), prefix(ag.entry().id(), ag.trace().candidatesExamined()));
		}
	}

	private final CostModel estimator;

	AgenticReport(CostModel estimator) {
		this.estimator = estimator;
	}

	// --- summary -------------------------------------------------------------------

	List<String> summary(Block b) {
		List<String> out = new ArrayList<>();
		int runs = b.agentic.size();
		int kmax = b.k * b.maxIterations;
		List<Outcome> ss = b.singleShot;
		List<Outcome> answerableSs = ss.stream().filter(o -> !o.entry().isShouldReturnNothing()).toList();
		int n = answerableSs.size();

		out.add("================================ k = " + b.k + " ================================");
		out.add(" arms: SS@" + b.k + "  single-shot (hybrid), final budget " + b.k + " — the equal-final-budget control");
		out.add("       AG@" + b.k + "  agentic, max-iterations " + b.maxIterations + ", final context capped to "
				+ b.k + "; " + runs + " runs");
		out.add("       SS@C  single-shot at C = distinct chunks the loop examined for that question (k <= C <= "
				+ kmax + ") — the candidate-budget control (retrieval-only)");

		long deterministic = ss.stream().filter(o -> ids(o.context()).equals(ids(b.prefix(o.entry().id(), b.k)))).count();
		boolean budgetOk = b.agentic.stream().flatMap(List::stream).allMatch(o -> o.context().size() <= b.k);
		out.add(" checks: SS@" + b.k + " context == precomputed ranking prefix for " + deterministic + "/" + ss.size()
				+ " questions;  AG final context <= " + b.k + " chunks in every run: " + budgetOk);

		List<String> ceiling = answerableSs.stream().filter(o -> !o.hitK()).map(o -> tag(o.entry().id())).toList();
		out.add(" rescue ceiling: SS@" + b.k + " hit@k misses = " + ceiling.size() + "/" + n + " " + ceiling
				+ "   (* = Slice-3 residual s7/s8/s15)");
		out.add("");

		// --- rescue accounting vs SS@k ---
		out.add(" RESCUE ACCOUNTING vs SS@" + b.k + "  (hit@k on the final context sent to generation; answerable n=" + n + ")");
		out.add(String.format("   %-5s %-10s %-26s %-26s %8s %9s", "run", "AG hit@k", "rescued", "broken", "same-hit", "same-miss"));
		out.add(String.format("   %-5s %-10s", "SS", count(answerableSs, Outcome::hitK) + "/" + n));
		for (int r = 0; r < runs; r++) {
			List<Outcome> ag = answerable(b.agentic.get(r));
			List<String> rescued = new ArrayList<>();
			List<String> broken = new ArrayList<>();
			int sameHit = 0;
			int sameMiss = 0;
			for (int i = 0; i < n; i++) {
				boolean s = answerableSs.get(i).hitK();
				boolean a = ag.get(i).hitK();
				String id = tag(ag.get(i).entry().id());
				if (!s && a) {
					rescued.add(id);
				}
				else if (s && !a) {
					broken.add(id);
				}
				else if (s) {
					sameHit++;
				}
				else {
					sameMiss++;
				}
			}
			out.add(String.format("   %-5s %-10s %-26s %-26s %8d %9d", "r" + (r + 1), count(ag, Outcome::hitK) + "/" + n,
					rescued.size() + " " + rescued, broken.size() + " " + broken, sameHit, sameMiss));
		}
		out.add("   (a hit@k break is always a cap eviction: the first pass IS SS@k, so a lost relevant chunk was evicted)");
		out.add("");

		// --- rescue class vs SS@C ---
		out.add(" RESCUES BY CLASS vs SS@C (candidate-budget control)");
		out.add(String.format("   %-5s %-36s %-36s", "run", "re-retrieval (SS@C also hits)", "reformulation-only (SS@C misses)"));
		for (int r = 0; r < runs; r++) {
			List<Outcome> ag = answerable(b.agentic.get(r));
			List<String> reRetrieval = new ArrayList<>();
			List<String> reformOnly = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				if (!answerableSs.get(i).hitK() && ag.get(i).hitK()) {
					(b.ssAtCandidatesHit(ag.get(i)) ? reRetrieval : reformOnly).add(tag(ag.get(i).entry().id()));
				}
			}
			out.add(String.format("   %-5s %-36s %-36s", "r" + (r + 1), reRetrieval.size() + " " + reRetrieval,
					reformOnly.size() + " " + reformOnly));
		}
		out.add("");

		// --- hit@1 (secondary) ---
		out.add(" hit@1 vs SS@" + b.k + " (secondary)   SS hit@1 = " + count(answerableSs, Outcome::hit1) + "/" + n);
		for (int r = 0; r < runs; r++) {
			List<Outcome> ag = answerable(b.agentic.get(r));
			List<String> promoted = new ArrayList<>();
			List<String> demoted = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				boolean s = answerableSs.get(i).hit1();
				boolean a = ag.get(i).hit1();
				if (!s && a) {
					promoted.add(tag(ag.get(i).entry().id()));
				}
				if (s && !a) {
					demoted.add(tag(ag.get(i).entry().id()));
				}
			}
			out.add(String.format("   r%d   AG hit@1 = %s   promoted %s   demoted %s", r + 1,
					count(ag, Outcome::hit1) + "/" + n, promoted, demoted));
		}
		out.add("");

		// --- stability across runs ---
		out.add(" STABILITY across " + runs + " runs — every question the loop acted on in any run, or whose outcome differs");
		out.add(String.format("   %-6s %-6s %-18s %-22s %-10s %-18s %-10s", "id", "SS@" + b.k, "AG hit@k r1..",
				"gate r1..", "C r1..", "SS@C hit r1..", "ctx chg"));
		List<String> stableRescue = new ArrayList<>();
		List<String> flakyRescue = new ArrayList<>();
		List<String> stableBreak = new ArrayList<>();
		List<String> flakyBreak = new ArrayList<>();
		for (int i = 0; i < ss.size(); i++) {
			Outcome s = ss.get(i);
			List<Outcome> per = perRun(b, i);
			boolean acted = per.stream().anyMatch(o -> o.gateInsufficient() || o.gateUnparseable() || o.trace().retried());
			boolean differs = !s.entry().isShouldReturnNothing() && per.stream().anyMatch(o -> !o.hitK().equals(s.hitK()));
			if (!acted && !differs) {
				continue;
			}
			out.add(String.format("   %-6s %-6s %-18s %-22s %-10s %-18s %-10s", tag(s.entry().id()),
					s.entry().isShouldReturnNothing() ? "srn" : hm(s.hitK()),
					join(per, o -> s.entry().isShouldReturnNothing() ? "-" : hm(o.hitK())),
					join(per, AgenticReport::gate),
					join(per, o -> String.valueOf(o.trace().candidatesExamined())),
					join(per, o -> s.entry().isShouldReturnNothing() ? "-" : hm(b.ssAtCandidatesHit(o))),
					join(per, o -> ids(o.context()).equals(ids(s.context())) ? "." : "Y")));
			if (!s.entry().isShouldReturnNothing()) {
				long hits = per.stream().filter(Outcome::hitK).count();
				if (!s.hitK() && hits == runs) {
					stableRescue.add(tag(s.entry().id()));
				}
				else if (!s.hitK() && hits > 0) {
					flakyRescue.add(tag(s.entry().id()) + "(" + hits + "/" + runs + ")");
				}
				else if (s.hitK() && hits == 0) {
					stableBreak.add(tag(s.entry().id()));
				}
				else if (s.hitK() && hits < runs) {
					flakyBreak.add(tag(s.entry().id()) + "(" + (runs - hits) + "/" + runs + ")");
				}
			}
		}
		out.add("   gate: S=SUFFICIENT  I=INSUFFICIENT  U=UNPARSEABLE (one letter per gate call); ctx chg: Y = final context != SS@" + b.k);
		out.add("   rescued in all runs: " + stableRescue + "   in some runs: " + flakyRescue);
		out.add("   broken  in all runs: " + stableBreak + "   in some runs: " + flakyBreak);
		out.add("");

		// --- gate activity ---
		out.add(" GATE ACTIVITY");
		out.add(String.format("   %-5s %-22s %-26s %-10s %-11s %-9s %-11s %s", "run", "INSUFF on answerable",
				"..with relevant pg in ctx", "INSUFF srn", "unparseable", "retried", "ctx changed", "stop reasons"));
		for (int r = 0; r < runs; r++) {
			List<Outcome> ag = b.agentic.get(r);
			List<Outcome> ans = answerable(ag);
			long insuffAns = ans.stream().filter(Outcome::gateInsufficient).count();
			long insuffWithRelevant = ans.stream()
					.filter(o -> o.gateInsufficient() && anyHit(o.entry(), b.prefix(o.entry().id(), b.k))).count();
			long srnTotal = ag.size() - ans.size();
			long insuffSrn = ag.stream().filter(o -> o.entry().isShouldReturnNothing() && o.gateInsufficient()).count();
			long unparseable = ag.stream().filter(Outcome::gateUnparseable).count();
			long retried = ag.stream().filter(o -> o.trace().retried()).count();
			long changed = 0;
			for (int i = 0; i < ag.size(); i++) {
				if (!ids(ag.get(i).context()).equals(ids(ss.get(i).context()))) {
					changed++;
				}
			}
			Map<String, Long> stops = ag.stream().collect(Collectors.groupingBy(o -> o.trace().stopReason().name(),
					LinkedHashMap::new, Collectors.counting()));
			out.add(String.format("   %-5s %-22s %-26s %-10s %-11d %-9s %-11s %s", "r" + (r + 1),
					insuffAns + "/" + ans.size(), String.valueOf(insuffWithRelevant), insuffSrn + "/" + srnTotal,
					unparseable, retried + "/" + ag.size(), changed + "/" + ag.size(), stops));
		}
		long meterMismatch = b.agentic.stream().flatMap(List::stream)
				.filter(o -> o.meteredCalls() != o.trace().reasoningCalls()).count();
		out.add("   check: metered API calls == trace reasoning calls for every question: "
				+ (meterMismatch == 0 ? "true" : "FALSE (" + meterMismatch + " mismatches)"));
		out.add("");

		// --- cost & latency ---
		out.add(" COST & LATENCY per question (n=" + ss.size() + ")  — reasoning [measured], generation [est]");
		out.add(String.format("   %-24s %10s %12s %12s %12s   %s", "arm", "reason/q", "reason $/q", "gen $/q",
				"total $/q", "ask() ms mean/p50/max"));
		out.add(costRow("SS@" + b.k, ss));
		for (int r = 0; r < runs; r++) {
			out.add(costRow("AG@" + b.k + " r" + (r + 1), b.agentic.get(r)));
		}
		double raiseTokens = ss.stream().mapToLong(o -> tokens(b.ranking.get(o.entry().id()), b.k, kmax)).average().orElse(0);
		out.add(String.format("   alternative \"just raise k\": SS@%d adds %.0f gen input tokens/q [est] = $%.6f/q, 0 extra calls",
				kmax, raiseTokens, CostModel.chatUsd(CostModel.GEN_MODEL, Math.round(raiseTokens), 0)));
		for (int r = 0; r < runs; r++) {
			double matched = b.agentic.get(r).stream()
					.mapToLong(o -> tokens(b.ranking.get(o.entry().id()), b.k, o.trace().candidatesExamined()))
					.average().orElse(0);
			out.add(String.format("   alternative matched SS@C (r%d): adds %.0f gen input tokens/q [est] = $%.6f/q, 0 extra calls",
					r + 1, matched, CostModel.chatUsd(CostModel.GEN_MODEL, Math.round(matched), 0)));
		}
		out.add("");

		// --- answer level ---
		out.add(" ANSWER-LEVEL (observational — generation is non-deterministic; judge sees the context actually sent)");
		out.add(String.format("   %-12s %-38s %s", "arm", "faithfulness S / P / U / x (answerable)", "abstention (srn)"));
		out.add(answerRow("SS@" + b.k, ss));
		for (int r = 0; r < runs; r++) {
			out.add(answerRow("AG@" + b.k + " r" + (r + 1), b.agentic.get(r)));
		}
		return out;
	}

	// --- per-question detail (persisted file only) -------------------------------------

	List<String> details(Block b) {
		List<String> out = new ArrayList<>();
		out.add("================================ k = " + b.k + " — per question ================================");
		for (int i = 0; i < b.singleShot.size(); i++) {
			Outcome s = b.singleShot.get(i);
			GoldenEntry e = s.entry();
			out.add("");
			out.add("[" + tag(e.id()) + "] " + e.type() + "  expected: "
					+ (e.isShouldReturnNothing() ? "(should-return-nothing)" : abbrev(e.filename()) + " " + e.relevantPages()));
			out.add("  question : " + oneLine(e.question()));
			out.add("  ranking  : " + labels(b.ranking.get(e.id())) + "   (original question, depth " + b.ranking.get(e.id()).size() + ")");
			out.add("  SS@" + b.k + "     : " + labels(s.context()) + "  hit@k=" + flag(s.hitK()) + "  verdict=" + s.verdict());
			out.add("             answer: " + oneLine(s.answer()));
			List<Outcome> per = perRun(b, i);
			for (int r = 0; r < per.size(); r++) {
				Outcome a = per.get(r);
				List<String> evicted = b.prefix(e.id(), b.k).stream()
						.filter(c -> !ids(a.context()).contains(id(c))).map(AgenticReport::label).toList();
				out.add("  AG r" + (r + 1) + "    : " + labels(a.context()) + "  hit@k=" + flag(a.hitK()) + "  hit@1=" + flag(a.hit1())
						+ "  stop=" + a.trace().stopReason() + "  C=" + a.trace().candidatesExamined()
						+ "  evicted=" + evicted + "  verdict=" + a.verdict());
				for (SufficiencyVerdict v : a.trace().verdicts()) {
					out.add("             gate: " + v.sufficiency()
							+ (v.missingInfo().isEmpty() ? "" : " — missing: " + oneLine(v.missingInfo())));
				}
				for (int q = 1; q < a.trace().queries().size(); q++) {
					out.add("             retry query: " + oneLine(a.trace().queries().get(q)));
				}
				out.add("             answer: " + oneLine(a.answer()));
			}
		}
		return out;
	}

	// --- helpers ---------------------------------------------------------------------

	private String costRow(String label, List<Outcome> arm) {
		double n = arm.size();
		double calls = arm.stream().mapToInt(Outcome::meteredCalls).sum() / n;
		double reasonUsd = arm.stream().mapToDouble(o -> CostModel.chatUsd(CostModel.GEN_MODEL, o.reasoningIn(), o.reasoningOut())).sum() / n;
		double genUsd = arm.stream().mapToDouble(o -> CostModel.chatUsd(CostModel.GEN_MODEL, o.genIn(), o.genOut())).sum() / n;
		long[] ms = arm.stream().mapToLong(Outcome::latencyMs).sorted().toArray();
		return String.format("   %-24s %10.2f %12s %12s %12s   %d / %d / %d", label, calls, usd(reasonUsd), usd(genUsd),
				usd(reasonUsd + genUsd), Math.round(Arrays.stream(ms).average().orElse(0)), ms[ms.length / 2], ms[ms.length - 1]);
	}

	private static String answerRow(String label, List<Outcome> arm) {
		List<Outcome> ans = answerable(arm);
		long s = ans.stream().filter(o -> "SUPPORTED".equals(o.verdict())).count();
		long p = ans.stream().filter(o -> "PARTIAL".equals(o.verdict())).count();
		long u = ans.stream().filter(o -> "UNSUPPORTED".equals(o.verdict())).count();
		long x = ans.size() - s - p - u;
		List<Outcome> srn = arm.stream().filter(o -> o.entry().isShouldReturnNothing()).toList();
		long ok = srn.stream().filter(o -> "ABSTAIN-OK".equals(o.verdict())).count();
		return String.format("   %-12s %-38s %s", label, s + "/" + ans.size() + "  /  " + p + "  /  " + u + "  /  " + x,
				ok + "/" + srn.size());
	}

	/** Estimated tokens of the ranking's chunks at positions [from, to). */
	private long tokens(List<RetrievedChunk> ranking, int from, int to) {
		long total = 0;
		for (int i = from; i < Math.min(to, ranking.size()); i++) {
			total += estimator.estimateChatTokens(ranking.get(i).chunk().text());
		}
		return total;
	}

	private static List<Outcome> perRun(Block b, int index) {
		return b.agentic.stream().map(run -> run.get(index)).toList();
	}

	private static List<Outcome> answerable(List<Outcome> arm) {
		return arm.stream().filter(o -> !o.entry().isShouldReturnNothing()).toList();
	}

	private static String count(List<Outcome> arm, Predicate<Outcome> test) {
		return String.valueOf(arm.stream().filter(test).count());
	}

	private static String gate(Outcome o) {
		if (o.trace().verdicts().isEmpty()) {
			return "-";
		}
		return o.trace().verdicts().stream().map(v -> v.sufficiency().name().substring(0, 1)).collect(Collectors.joining());
	}

	private static String join(List<Outcome> per, java.util.function.Function<Outcome, String> f) {
		return per.stream().map(f).collect(Collectors.joining("/"));
	}

	static boolean isHit(GoldenEntry e, RetrievedChunk rc) {
		return e.isHit(rc.chunk().provenance().filename(), rc.chunk().provenance().pageNumber());
	}

	static boolean anyHit(GoldenEntry e, List<RetrievedChunk> chunks) {
		return chunks.stream().anyMatch(rc -> isHit(e, rc));
	}

	private static String id(RetrievedChunk rc) {
		return rc.chunk().provenance().documentId() + "#" + rc.chunk().provenance().chunkIndex();
	}

	private static List<String> ids(List<RetrievedChunk> chunks) {
		return chunks.stream().map(AgenticReport::id).toList();
	}

	private static String tag(String id) {
		return SLICE3_RESIDUALS.contains(id) ? id + "*" : id;
	}

	private static String hm(Boolean hit) {
		return hit ? "hit" : "miss";
	}

	private static String flag(Boolean b) {
		return b == null ? "n/a" : b.toString();
	}

	static String label(RetrievedChunk rc) {
		return abbrev(rc.chunk().provenance().filename()) + ":p" + rc.chunk().provenance().pageNumber();
	}

	private static String labels(List<RetrievedChunk> chunks) {
		return chunks.isEmpty() ? "(none)" : chunks.stream().map(AgenticReport::label).collect(Collectors.joining(" "));
	}

	static String abbrev(String filename) {
		if (filename.startsWith("huberman")) {
			return "HUB";
		}
		if (filename.startsWith("Rajeet")) {
			return "RES";
		}
		return filename;
	}

	private static String oneLine(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").strip();
	}

	private static String usd(double amount) {
		return String.format("$%.6f", amount);
	}
}
