# Slice 2 — Checkpoint C (persistence + polish + clean dual-judge A/B, Step 3)

**Status:** paused for review — this closes Slice 2. Implemented in the working tree on
branch `slice-1-thin-e2e` (not yet committed; the dedicated `slice-2-*` branch is still to
be created before committing). Builds on Checkpoints A (retrieval) and B (faithfulness +
cross-model validation).

**Date:** 2026-10-29.

---

## Judge default

The normal-run judge default is **`gpt-6-luna`** (cheap) — confirmed in both the harness
`@Value("${documind.eval.judge-model:gpt-6-luna}")` and the pom eval-profile default. `sol`
remains a one-flag override: `-Ddocumind.eval.judge-model=gpt-6-sol`. A normal run costs
**~$0.015**.

## What landed (Step 3)

1. **Persisted per-question results** (`eval/RunReport`). Every run of
   `FaithfulnessEvaluation` now writes a timestamped, diff-friendly text file to
   `target/eval/results-<UTC>.txt`. Per question: id, type, question, generated answer,
   retrieved chunks as `file:page(score)` in rank order, `hit@1`/`hit@k`, expected pages,
   judge verdict + rationale. A SUMMARY block at the top carries retrieval hit-rate,
   faithfulness, abstention, and the cost summary. Questions are emitted in a stable order
   (id: alpha prefix then **numeric** suffix) with a fixed field layout, so a plain `diff`
   of two run files reads cleanly — the intended Slice-3 "hit@1 76% → X% after reranking"
   comparison. The file's retrieval hit-rate reproduces Step 1's **76.0%@1 / 96.0%@k**, a
   free cross-check that the consolidated harness scores retrieval the same way.

2. **Fixed-answer dual-judge A/B** (`eval/DualJudgeEvaluation`, a separate deliberate
   pass). Generates one answer set, **freezes** it, and runs **both** judges over the
   identical frozen answers — so a disagreement is attributable to the judge alone, unlike
   Checkpoint B's two-run comparison (which judged different regenerated answers). Both the
   frozen answers (with full retrieved chunk text) and both judges' verdicts are persisted
   to `target/eval/dual-judge-<UTC>.json`; a later run can re-judge the **same** answers
   with `-Ddocumind.eval.frozen-answers=<path>` (generation + embedding cost then $0).

3. **`target/` stays gitignored** (verified: `git check-ignore target/eval/…` → ignored).
   Only the golden set and harness code are tracked; run outputs are not.

Shared refactors: `GoldenEntry.isHit(file,page)` is now the single hit definition (used by
both harnesses); `CostModel` prices the judge by its configured model id (luna $0.10/$0.50,
sol $2/$10) and exposes its summary as reusable lines + a static `chatUsd`.

### How to run

```bash
# normal consolidated run (cheap luna judge) -> target/eval/results-*.txt
OPENAI_API_KEY=... ./mvnw test -Peval -Dtest=FaithfulnessEvaluation
# clean dual-judge A/B (luna vs sol over frozen answers) -> target/eval/dual-judge-*.json
OPENAI_API_KEY=... ./mvnw test -Peval -Dtest=DualJudgeEvaluation
# re-judge the SAME frozen answers later, no regeneration:
OPENAI_API_KEY=... ./mvnw test -Peval -Dtest=DualJudgeEvaluation \
    -Ddocumind.eval.frozen-answers=target/eval/dual-judge-<UTC>.json
```

---

## Numbers

**Normal run (luna judge), this sample** — retrieval `76.0%@1 / 96.0%@k` (matches Step 1);
faithfulness **96.0% SUPPORTED (24/25)**, **1 PARTIAL** (`s14`: the menopause answer added
"discuss options with a healthcare provider", which the judge found not stated in the
retrieved context), 0 UNSUPPORTED; abstention **100% (5/5)**. Est. cost **$0.0151**.

> This refines Checkpoint B's flat 100%: generation is non-deterministic, so faithfulness
> floats in the high-90s and the occasional paraphrased answer overextends the passage by a
> clause. That is the metric doing its job, not a regression — and now every run is
> persisted so the variation is visible and comparable.

**Fixed-answer dual-judge A/B** — both judges over the **identical** 30 frozen answers:

| | agreement | disagreements |
|---|---|---|
| luna vs sol | **29 / 30 (96.7%)** | **1** — `s15` |

`s15` (paraphrased, "mind racing at bedtime"): **luna = PARTIAL, sol = SUPPORTED**. luna
held that relaxed-rest/NSDR advice given for getting back to sleep after waking "overextends
the passage" when applied to racing thoughts at bedtime; sol accepted it as grounded. Note
the direction: **the self-judge was the _stricter_ one** — the opposite of the self-leniency
the cross-model check was guarding against. Both judges received byte-identical input
(68,377 prompt tokens each), confirming a clean isolation of the judge variable.

Measured A/B cost **$0.1649**: sol judge **$0.1494** (dominates), luna judge $0.0079,
generation (est) $0.0075, embeddings $0.0001.

---

## Flags / caveats

1. **Faithfulness is a per-run sample, not a fixed score.** Non-deterministic generation
   means the specific PARTIAL entry moves run to run (`s14` in the normal run, `s15` in the
   A/B). The persisted files are the way to track this; a fixed seed/temperature-0 for the
   generator would stabilise it if we later want a deterministic headline.
2. **Reproducibility unchanged:** on a fresh clone the 10 `r*` entries skip (résumé absent),
   so a clone scores the Huberman half. Recorded as `SKIP` rows in the results file.
3. **Cost confidence unchanged:** judge = measured; embeddings + generation = jtokkit
   estimates; dollar prices are ~Oct 2026 aggregator figures, verify against OpenAI.

---

## Build order

- [x] Design approved; Step 1 retrieval (Checkpoint A); Step 2 faithfulness + cross-model
      judge validation (Checkpoint B).
- [x] **Step 3 — persisted diff-friendly per-question results; fixed-answer dual-judge A/B
      with reproducible frozen artifact; `target/` gitignored. >>> CHECKPOINT C — closes
      Slice 2.**

### Follow-ups parked for later slices
- Slice 3: add reranking, re-run, diff `results-*.txt` to see `hit@1` move (the 76%→? gap).
- Optional: temperature-0 generation for a deterministic faithfulness headline.
- Create the `slice-2-*` branch and commit before Slice 3 work begins.
