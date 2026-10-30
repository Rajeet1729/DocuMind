# Slice 4 — Checkpoint A (agentic loop: implemented, measured, conclusion pending review)

**Status:** paused for review of the rescue accounting and 3-run stability. Implemented in
the working tree on `main` (not committed). One change only: an agentic retrieval strategy
behind a new application-layer seam, default off. **No net-value conclusion is drawn here.**

**Date:** 2026-10-30.

---

## What landed

- `RetrievalStrategy` seam in `application.service`: `SingleShotRetrieval` (one
  `ChunkStore.search`, the default — Slice-3 behaviour) and `AgenticRetrieval` (bounded
  retrieve → gate → reformulate → retry), chosen by `documind.retrieval.agentic.enabled`
  (default **false**). `QuestionAnsweringService` now takes the strategy; `ask()`, the
  prompt, and the canned-abstention path are unchanged. Upstream (controller, use case) untouched.
- `ReasoningModel` outbound port (`judgeSufficiency`, `reformulate`) + `ChatClientReasoningModel`
  adapter — the Slice-1 deferred ChatClient decision, resolved narrowly ([ADR 0003](../adr/0003-reasoning-model-port-for-agentic-loop.md)).
  Generation stays on the injected `ChatClient`.
- Loop rules: LLM gate (no score threshold); **no gate after the final pass**; per-pass
  lists fused by RRF and **capped to top-k**; **ties go to the original query**; stop on
  SUFFICIENT / UNPARSEABLE gate / blank reformulation / no new chunks / `max-iterations=2`.
- Harness: `AgenticEvaluation` + `AgenticReport` (paired arms in one JVM over one index,
  recorder so the judge sees the exact context used, metered reasoning calls).
  `FaithfulnessEvaluation`/`DualJudgeEvaluation` now fail fast under the agentic flag (their
  second `search()` would no longer match the generator's context). `CostModel` gains a
  measured "agentic reasoning" line (printed only when non-zero).
- Tests (offline): `AgenticRetrievalTest` (11), `ChatClientReasoningModelTest` (6),
  `RetrievalStrategyWiringTest` (3); `QuestionAnsweringServiceTest` updated; context-load green.

## The two review clarifications — as resolved

1. **Cap vs monotonicity → cap.** Final context capped to k; the "hit@k monotone by
   construction" claim is **dropped**. Every hit@k break is, by construction, a cap eviction
   (the first pass *is* SS@k), and evictions are logged per question.
2. **Budget control on two axes.** Generation budget is equal by construction (cap = k), so
   **SS@k** is the equal-final-budget control. The candidate budget is controlled by
   **SS@C**: single-shot with the original question at C = distinct chunks the loop examined
   for that question. A rescue SS@C also gets = **re-retrieval**; one it misses =
   **reformulation-only** (the only "added intelligence" signal).

## Deviations found during verification (before any paid run)

- **`gpt-6-luna` rejects `temperature=0`** ("Only the default (1) value is supported", live
  API). The approved temperature-0 mitigation is impossible; the knob was dropped and
  non-determinism is measured (3 runs) instead.
- **The cap + ties-to-original rule admits ⌊k/2⌋ reformulation chunks** (two passes, no
  overlap interleave q1r1, q2r1, q1r2, …). At **k=1 the loop cannot change the context at
  all** — so no k=1 arm was run. Pinned by a unit test.
- **Rescue ceiling is tiny by construction:** only questions SS@k misses can be rescued.
  Predicted 1/25 at k=2 from the Slice-3 artifact; measured 2/25 (see ranking drift below).

## Numbers — `target/eval/agentic-20261030T172421Z.txt` (k=2 and k=4, 3 agentic runs each)

Corpus ingested **once**; SS once and AG ×3 per k against the same index (isolates the
loop's LLM stochasticity from ingestion). Hybrid primitive (candidate-k 20, rrf-k 60).
Checks: SS@k == precomputed ranking prefix **30/30** (both k); AG final context ≤ k in every
run; metered API calls == traced reasoning calls for every question.

### Rescue accounting (hit@k on the context sent to generation; answerable n=25)

| k | ceiling (SS@k misses) | SS@k hit@k | AG hit@k r1/r2/r3 | rescued | broken | hit@1 SS → AG |
|---|---|---|---|---|---|---|
| 2 | 2/25 (`s8*`, `s15*`) | 23/25 | 23/23/23 | **0/0/0** | **0/0/0** | 22 → 22/22/22 |
| 4 | 0/25 | 25/25 | 25/25/25 | 0/0/0 | **0/0/0** | 22 → 22/22/22 |

Rescues by class vs SS@C: none to classify (0 re-retrieval, 0 reformulation-only) in all 6 runs.

### 3-run stability

Stable in every dimension that was measured: same gate verdict per question in all 3 runs
(both k), no question flipped hit/miss, no rescue or break in any run. Variation was only in
*which* chunks the reformulation pulled (C and context-changed differ run to run), never in
the outcome.

### Mechanism, per question (k=2)

- **`s8*` (QQRT) — gate right 3/3, reformulation drifted 3/3.** Gate: INSUFFICIENT with an
  accurate "doesn't say what the letters stand for" every run. The three retry queries
  ("…acronym full form meaning…framework components definition") pulled the **résumé's
  second page-1 chunk** (it matches "framework"/"components") in r1/r2 and nothing new in r3;
  **p5 was never retrieved by a retry.** In r1/r2 the overlap boost promoted `RES:p1` to rank 1.
  SS@C hit in r1/r2 (C=3 reaches p5 at the original rank 3) — i.e. plain depth would have
  found it where the reformulation did not.
- **`s15*` (racing mind) — gate SUFFICIENT 3/3, loop never fired.** The context (supplements,
  p4/p6) holds *an* answer, not the golden one (relaxation techniques, p1–2). A sufficiency
  gate cannot distinguish "sufficient" from "best available".
- **Gate activity:** INSUFFICIENT on 2/25 answerable at k=2 (`s8`, `r7`) and 1/25 at k=4
  (`r7`) — `r7`'s context already holds the right page (the résumé lists Liferay DXP but not
  project experience; the gate is arguably correct, the answer is "not stated"). INSUFFICIENT
  on **5/5 should-return-nothing in every run** — the loop always pays 2 calls on unanswerable
  questions. Abstention held 5/5 in every arm.

### Cost & latency (per question, n=30)

| arm | reasoning calls/q | reasoning $/q [measured] | total $/q | ask() ms mean / p50 |
|---|---|---|---|---|
| SS@2 | 0 | 0 | $0.000158 | 2444 / 2352 |
| AG@2 (r1–r3) | 1.23 | ~$0.000192 | ~$0.000350 (**2.2×**) | ~4600 / ~3860 (**~1.9×**) |
| SS@4 | 0 | 0 | $0.000281 | 2404 / 2445 |
| AG@4 (r1–r3) | 1.20 | ~$0.000310 | ~$0.000590 (**2.1×**) | ~4560 / ~3940 (**~1.9×**) |

"Just raise k" reference: SS@4 vs SS@2 adds ~1227 gen input tokens/q ≈ $0.000123/q, 0 extra
calls. Whole run (all arms + harness rankings + judge): **$0.154**.

### Answer level (observational)

Faithfulness SUPPORTED 25/25 in every arm except SS@4 (24/25, 1 PARTIAL) — generation noise,
not a loop signal. Abstention 5/5 everywhere.

## Reliability flags

- **Small N, tiny ceiling.** 2 rescuable questions at k=2, 0 at k=4; both are Slice-3
  residuals. The k=2 result is effectively a test on `s8` and `s15`.
- **Hybrid ranking drifts across ingestions.** `s15`'s ranking was p6 p2 p3 p4 (Slice-3
  artifact), p6 p4 p2 p3 (smoke), p4 p6 p2 p3 (this run) — three ingestions, three orders.
  Within a run it is deterministic (30/30), and all comparisons here are paired within one
  run, so this A/B stands; but the Slice-3 "exactly reproducible" claim held for hit counts,
  not for in-window rank order, and the rescue ceiling itself can move between runs.
  (Likely causes, unverified: random `documentId` UUIDs in the RRF tie-break key, embedding
  float jitter.)
- **Non-determinism is unmitigable on this model** (no temperature control); measured as stable
  across 3 runs here, which is a statement about this corpus and these questions only.
- Labels assumed correct (Slice-2 reviewed).

## Artifacts (gitignored under `target/`)

- `target/eval/agentic-20261030T172421Z.txt` — full run (summary + per-question detail,
  including every gate verdict, retry query, eviction, and answer).
- `target/eval/agentic-20261030T165826Z.txt` — smoke run (k=2, 1 run), harness validation.

## Next (not done — awaiting review)

- Draw the net-value conclusion from the above.
- If approved: commit Slice 4 (code + tests + ADR 0003 + this checkpoint), flag default off.
