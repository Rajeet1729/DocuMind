# Slice 2 — Checkpoint B (answer faithfulness + abstention, Step 2)

**Status:** paused for review. Slice-2 Step 2 is implemented in the working tree on
branch `slice-1-thin-e2e` (not yet committed; the dedicated `slice-2-*` branch is still
to be created before committing). These are the **first paid chat calls** the project has
made — generation (`gpt-6-luna`) + judge (`gpt-6-luna`), on top of Step 1's embeddings.

**Date:** 2026-10-29.

**Resume at:** Step 3 — estimated cost summary consolidation (embeddings + generation +
judge) and a saved per-question results file for run-over-run comparison. *(A first-pass
cost summary already prints here; Step 3 is the polish + persistence.)*

---

## What landed (Step 2)

A second `@Tag("eval")` harness that scores the **generated answer**, reusing Step 1's
corpus ingestion and golden set.

- **`eval/Judge`** — LLM-as-judge wrapper over a `ChatClient` pinned to `gpt-6-luna`
  (model set explicitly via `ChatOptions`, independent of the app's configured model).
  Two judgements, two verbatim system prompts (logged by the harness):
  - **faithfulness** → `{SUPPORTED, PARTIAL, UNSUPPORTED}` + rationale; judges the answer
    against the **full retrieved context only**, explicitly *not* against the golden
    `expectedAnswer` (that is correctness, a separate metric);
  - **abstention** → `{abstained true/false}` + rationale, for should-return-nothing.
  - Prompts ask for bare JSON (no fences) which we parse ourselves, so the logged prompt
    is byte-for-byte what is sent (Spring AI's structured-output converter would have
    appended its own format instructions). Each call captures the API's **real** token
    usage.
- **`eval/CostModel`** — estimated cost accounting. Judge tokens are **measured** from the
  API; embedding and generation tokens are **jtokkit estimates** (`cl100k_base` for the
  embedding model, `o200k_base` for the chat model). Prices labelled, dated, and flagged
  as third-party aggregator figures to verify against OpenAI.
- **`eval/FaithfulnessEvaluation`** — the harness: for each answerable entry it
  re-derives the retrieved chunks with its own `search()` (full text for the judge; the
  pipeline's internal `search()` returns the same deterministically), runs the real
  `ask()`, and judges faithfulness; for each should-return-nothing entry it judges
  abstention. Prints the verbatim judge prompts, a per-question line, the faithfulness
  summary (headline = share SUPPORTED, PARTIAL reported separately), the abstention
  metric, and the cost summary.
- **`pom.xml`** — added `com.knuddels:jtokkit:1.1.0` (test scope), pulled forward from
  the Step-3 plan because the Step-2 cost summary needs it.

### How to run

```bash
cd "/media/asus/HDD/Local disk E/DocuMind"
OPENAI_API_KEY=... ./mvnw test -Peval -Dtest=FaithfulnessEvaluation   # Docker + paid calls
```

---

## Numbers (top-k = 4, 30 entries, all ingested — résumé present locally)

**Faithfulness** (factual + paraphrased; abstention excluded):

| Category | n | SUPPORTED | PARTIAL | UNSUPPORTED | unparseable |
|---|---|---|---|---|---|
| factual | 17 | 100% (17/17) | 0 | 0 | 0 |
| paraphrased | 8 | 100% (8/8) | 0 | 0 | 0 |
| **OVERALL** | 25 | **100% (25/25)** | **0** | **0** | **0** |

**Abstention** (should-return-nothing, its own metric): **100% (5/5)** correctly declined.

**Estimated cost for this run: ~$0.0152** — judge **$0.0077 (measured)**, generation
**$0.0074 (est)**, embeddings **$0.0001 (est)**. 30 generation calls, 30 judge calls, 60
query embeddings (2 per question: the harness `search()` + the one inside `ask()`).

---

## What the numbers surface — read the 100% with these caveats

1. **The judge is the same model as the generator (`gpt-6-luna` judges `gpt-6-luna`).**
   This is as specified for Step 2, but self-evaluation is known to run lenient — a model
   tends to ratify its own phrasing. A perfect 100% SUPPORTED is exactly the kind of
   result that bias produces, so it should be read as "no self-detected ungrounded
   claims," not as independently verified faithfulness. A cross-model judge is the obvious
   follow-up if we want the number to carry more weight.
2. **Faithfulness is a lower bar than correctness, and that is working as intended.**
   The pipeline is instructed to ground in context and to decline otherwise, so a
   grounded 100% is plausible — several answers essentially restate the retrieved passage.
   Faithfulness says the answers invent nothing; it does *not* say they are all correct.
3. **`s8` is the nicest data point.** It was the one Step-1 retrieval miss (QQRT page
   never surfaced). The answer correctly said the context mentions QQRT but does not
   expand the acronym, and the judge graded it SUPPORTED — the model declined in place
   rather than fabricating the expansion. Faithfulness and retrieval-correctness are
   decoupled, and this is the clean example.

---

## Cross-model judge validation (de-biasing the self-judge)

To test caveat #1, the faithfulness pass was re-run with **only the judge model changed**
to `gpt-6-sol` (verified a live API id via the account's `/v1/models` list; ~20× luna's
price). Answers are still generated by `gpt-6-luna`; the judge system prompt is byte-for-
byte identical. The judge model is now a config value
(`documind.eval.judge-model`, default `gpt-6-luna`), swapped on the command line with no
code edit: `./mvnw test -Peval -Ddocumind.eval.judge-model=gpt-6-sol`.

| Category | n | luna SUP/PART/UNSUP | sol SUP/PART/UNSUP |
|---|---|---|---|
| factual | 17 | 17 / 0 / 0 | 17 / 0 / 0 |
| paraphrased | 8 | 8 / 0 / 0 | 8 / 0 / 0 |
| **faithfulness overall** | 25 | **25 / 0 / 0** | **25 / 0 / 0** |
| abstention | 5 | 5 correct | 5 correct |

**The two judges agree on every one of the 30 entries — zero disagreements.** The stronger
judge did not overturn a single SUPPORTED or abstention verdict, which is real evidence
the self-judge's 100% is not just self-leniency. It is in fact a stronger signal than a
single answer set judged twice: the two runs scored **independently generated** answers
(generation is non-deterministic), so both judges found every answer fully grounded across
*two* answer samples. Measured cost of the sol run: **$0.1456** judge / **$0.1530** total.

Caveat on method: because the two runs judged different generated answers, this isolates
"does a stronger judge find ungrounded claims the self-judge missed" (it did not) rather
than a strict same-answer A/B. Judging one fixed answer set with both judges is a clean
follow-up, and folds naturally into Step 3's persisted per-question results.

## Flags

1. **The canned no-answer path never fires.** With a 2-document corpus, `search()` always
   returns top-k chunks, so `QuestionAnsweringService`'s `NO_CONTEXT_ANSWER` (returned
   only on *empty* retrieval) is never hit for the should-return-nothing entries. What we
   actually measure as "abstention" is the **model declining** ("not stated in the
   context") when handed irrelevant chunks, per its system-prompt instruction — which it
   did on all 5. The design note "correct = pipeline returns the canned no-answer" is
   therefore satisfied at the *behaviour* level (declined), not by the literal canned
   string. Worth deciding in a later slice whether a similarity floor should route weak
   retrievals to the canned path explicitly.
2. **Reproducibility unchanged from Step 1.** All 30 entries ran here because the résumé
   is present locally; on a fresh clone the 10 `r*` entries skip (source file absent),
   so a clone would score the Huberman half only.
3. **Cost figures are mixed-confidence by design.** Judge = measured; embeddings +
   generation = jtokkit estimates (generation input adds a ~70-token/call fixed allowance
   for the system prompt + message framing the harness cannot see). Dollar prices are
   third-party aggregator figures (~Oct 2026), not OpenAI's own page.

---

## Decisions carried / made

- Faithfulness = answer-vs-retrieved-context grounding; the judge is **not** shown the
  golden `expectedAnswer`.
- Judge sees **full retrieved chunk text**, re-derived by the harness's own `search()`
  (the "one redundant query-embedding" the S1 decision allowed; counted in the cost).
- Abstention scored as its **own metric**, kept out of the faithfulness headline.
- Judge system prompts **logged verbatim**; judge token usage **measured**, not estimated.
- `jtokkit` pulled forward from Step 3 for the Step-2 cost summary.

---

## Build order

- [x] Design approved (filename-match scoring fix; corpus split; S1; jtokkit; abstention)
- [x] Step 1 — retrieval + hit-rate, printing. (Checkpoint A, approved.)
- [x] **Step 2 — faithfulness (LLM-as-judge) + abstention; verbatim judge prompts; first
      paid chat calls; first-pass cost summary. >>> CHECKPOINT B (here).**
- [ ] Step 3 — consolidated estimated cost summary and a saved per-question results file
      for run-over-run comparison.
