# Slice 2 — Checkpoint A (retrieval eval harness, Step 1)

**Status:** paused for review. Slice-2 Step 1 is implemented in the working tree on
branch `slice-1-thin-e2e` (not yet committed; a dedicated `slice-2-*` branch is still to
be created before committing). The eval harness — **retrieval hit-rate** — produced its
first real baseline numbers. No chat/judge calls yet (embeddings only).

**Date:** 2026-10-28.

**Resume at:** Step 2 — answer faithfulness (LLM-as-judge, `gpt-6-luna`). These are the
first *paid chat* calls, held until this checkpoint is approved.

---

## What landed (Step 1)

A manually-run harness that scores the live retrieval path against the version-controlled
golden set and prints a report.

- **`src/test/resources/eval/golden-set.json`** — 30 entries
  (17 factual, 8 paraphrased, 5 should-return-nothing). Each entry now carries a
  **`filename`** field (approved change): a hit must match BOTH the correct file AND a
  page in `relevantPages`.
- **`src/test/resources/eval/corpus/`** — the two source PDFs. `huberman_sleep.pdf`
  (public) is committed via a `.gitignore` negation; `RajeetGoyalResume_jpmc.pdf`
  (personal) stays untracked.
- **`eval/GoldenEntry`, `eval/GoldenSet`** — golden-set model + loader with structural
  warnings (unique ids, known types, empty-pages ⇔ should-return-nothing).
- **`eval/RetrievalEvaluation`** — `@Tag("eval")` `@SpringBootTest` harness. Ingests
  each corpus PDF once via the real chunker + `ChunkStore` (corpus embedded once per
  run), then calls the same `ChunkStore.search(question, topK)` the app uses and scores
  hit@1 / hit@k. Prints golden-set flags, a per-question line, and a summary.

### How to run

```bash
cd "/media/asus/HDD/Local disk E/DocuMind"
OPENAI_API_KEY=... ./mvnw test -Peval        # needs Docker; makes real embedding calls
```

Excluded from the normal build two ways: the class is named `*Evaluation` (Surefire only
collects `*Test`), **and** `@Tag("eval")` is in `excludedGroups`. It runs only under the
`eval` Maven profile, which clears the exclusion, includes `**/eval/*Evaluation.java`,
and sets `enableAssertions=false`.

---

## Baseline numbers (top-k = 4, 30 entries)

| Category | n | hit-rate@1 | hit-rate@4 |
|---|---|---|---|
| factual | 17 | 76.5% (13/17) | 94.1% (16/17) |
| paraphrased | 8 | 75.0% (6/8) | 100% (8/8) |
| **OVERALL** | 25 | **76.0% (19/25)** | **96.0% (24/25)** |

should-return-nothing: 5 entries held out (abstention is answer-level, scored in Step 2).
Skipped: 0 (résumé present locally).

---

## What the numbers surface

- **The filename-match fix is demonstrably necessary, not cosmetic.** Résumé p1 and
  Huberman p1 collide on page number; on nearly every `r*` question `HUB p1` appears in
  the top-4 next to the correct `RES p1` (e.g. `r2`: `RES p1, HUB p1, HUB p6, HUB p8`).
  Page-only matching would have counted `HUB p1` as a bogus second hit and inflated
  hit-rate@k. File+page matching rejects it.
- **The one hit@4 miss — `s8`** ("What does the QQRT framework stand for?", expected
  Huberman p5). Top-4 was `RES p1, RES p1, HUB p6, HUB p1`: the correct page never
  surfaced and the **top two results came from the résumé** — "framework" pulled résumé
  tech content over the sleep doc. A genuine embedding miss and a clean
  cross-document-contamination example.
- **The hit@1 vs hit@4 gap is a handful of near-misses** where the right page sits at
  rank 2–4: `s7` (p5 @ rank 4), `s17` (p5 @ rank 2), `s11`, `s15`. These pull @1 to ~76%
  while @4 is ~96%.

---

## Flags

1. **Huberman page count = 9**, but the PDF reports 8 pages — the reader emits a trailing
   9th page. No scoring impact (golden set maxes at page 6); no impossible-page flags
   fired.
2. **Reproducibility:** the résumé is intentionally uncommitted, so on a fresh clone the
   10 `r*` entries are **skipped** (flagged, not failed). Only the Huberman half is
   reproducible for others — a consequence of keeping the personal PDF out of the repo.
3. **PDFBox + `-ea`:** PDFBox trips an internal `assert` on this PDF under Surefire's
   default `-ea`. Production ingestion runs without `-ea`, so the eval profile sets
   `enableAssertions=false` to mirror production. (This disables assertions for the eval
   run only, not for `mvn test`.)

---

## Decisions carried from the approved design

- **S1 (no production change):** harness uses `search()` for hit-rate and (in Step 2)
  `ask()` for the answer; one deterministic redundant query-embedding per question,
  reported in the cost summary.
- **jtokkit** for estimated token/cost accounting (Step 3), clearly labelled *estimated*.
- **should-return-nothing** scored as a separate **abstention** metric (Step 2), kept out
  of factual/paraphrased hit-rate.
- `OPENAI_API_KEY` from the environment; never hardcoded.

---

## Build order

- [x] Design approved (filename-match scoring fix; corpus split; S1; jtokkit; abstention)
- [x] **Step 1 — retrieval + hit-rate, printing. >>> CHECKPOINT A (here).**
- [ ] Step 2 — answer faithfulness (LLM-as-judge, `gpt-6-luna`); abstention for
      should-return-nothing; log the judge prompt. *First paid chat calls.*
- [ ] Step 3 — estimated cost summary (embeddings + generation + judge) and a saved
      per-question results file for run-over-run comparison.
