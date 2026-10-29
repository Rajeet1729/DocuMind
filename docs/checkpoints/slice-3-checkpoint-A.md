# Slice 3 — Checkpoint A (hybrid retrieval: BM25 + vector RRF, measured)

**Status:** paused for review of the numbers. Implemented in the working tree on `main`
(not committed). One change only: hybrid retrieval behind the `ChunkStore` seam. Reranking
remains shelved.

**Date:** 2026-10-30.

---

## What landed

Hybrid retrieval inside `ElasticsearchChunkStore`, toggled by
`documind.retrieval.hybrid.enabled` (default **false** = vector-only = Slice-2 baseline,
byte-identical). When on: a BM25 `match` on `content` and a vector `knn` on `embedding`
are issued to Elasticsearch via the native client (`VectorStore.getNativeClient()`), each
to depth `candidate-k`, then fused by **Reciprocal Rank Fusion in-process**
(`score = Σ 1/(rrf_k + rank)`, `rrf_k=60`). ES-native RRF is Enterprise-licensed (403 on
basic), so fusion is done in the adapter over two basic-license queries. No new port;
nothing upstream changed; both harnesses call the same `search()`.

Live mapping confirmed at runtime: index `documind-chunks` fields `[metadata, embedding,
id, content]`, `content` is `{"type":"text",...}` (standard analyzer → BM25 works).

Deterministic (ES BM25 + kNN + embedding), so the A/B is reproducible — the hybrid
numbers were identical across the cheap retrieval-only smoke and the full paid run.

## Numbers — baseline (vector-only) → hybrid, as counts

| scope | n | hit@1 | hit@k |
|---|---|---|---|
| **OVERALL** | 25 | **19/25 (76%) → 22/25 (88%)** | **24/25 (96%) → 25/25 (100%)** |
| factual | 17 | 13/17 → 15/17 | 16/17 → 17/17 |
| paraphrased | 8 | 6/8 → 7/8 | 8/8 → 8/8 |
| huberman_sleep.pdf | 17 | 12/17 (71%) → 14/17 (82%) | 16/17 → 17/17 |
| RajeetGoyalResume_jpmc.pdf | 8 | 7/8 (88%) → **8/8 (100%)** | 8/8 → 8/8 |

**hit@1 +12 pts (+3 entries); hit@k +4 pts (+1 entry); zero hit@k regressions** (checked
per-entry, not just aggregate).

Per-entry changes (by source document):
- `r5` (résumé, "which embedding model"): hit@1 ✗→✓ — BM25 matched the résumé.
- `s8` (sleep, QQRT): hit@k ✗→✓ — **rescued into top-4** (the baseline's only hit@k miss, the cross-doc contamination case). Still hit@1 ✗ (p5 at rank 3).
- `s11`, `s17` (sleep): hit@1 ✗→✓.
- Unchanged misses: `s7` (p5 @ rank 2), `s15` (p2 @ rank 3).

## Findings

1. **Hybrid clearly improved retrieval, and fixed the keyword cases.** QQRT (`s8`) — the
   exact-term question embeddings fumbled — is now retrieved; cross-doc contamination
   (`r5`, `s8`) is resolved. hit@k hit 100%.
2. **Per-document divergence — the OPPOSITE of the hypothesis.** The feared tech-token
   mangling (`text-embedding-3-small`, `300+`, `7.87` split by the standard analyzer) did
   **not** hurt the résumé: it went to **perfect hit@1 (8/8)**, including `r5`
   (text-embedding-3-small) and `r3` (300+ QPS). Reason: the résumé is tiny (2 chunks,
   one page), so hit@1 is really "is the résumé ranked above the sleep doc," and BM25 nails
   that on rare proper nouns (TCS, Kafka, Chandigarh). The *sleep* doc is where misses
   remain.
3. **The residual error is intra-document adjacent-page confusion, not cross-doc.** All
   three remaining hit@1 misses (`s7`, `s8`, `s15`) are the same pattern: page 6 (the
   supplement *summary*) outranks page 5 (the *specific* supplements) because they share
   vocabulary. Neither BM25 nor vectors separate adjacent pages here. This is now the
   dominant failure mode — a chunking/granularity problem more than a fusion one.
4. **candidate-k=20 is a no-op ceiling on this corpus.** Total corpus = **11 chunks**
   (huberman 9, résumé 2), so `candidate-k=20` pulls the *entire* corpus into both lists
   and RRF reorders all of it. candidate-k prunes nothing here and cannot be meaningfully
   studied unless set below 11. The lift above came purely from fusion reordering the full
   corpus, which also means it is a best case — on a large corpus candidate-k and recall
   would matter.

## Observational (not the signal)

- Faithfulness moved 96% → 92% SUPPORTED (24/25 → 23/25); **confounded** (non-deterministic
  generation + changed context), as flagged. Not a hybrid signal.
- Cost: baseline $0.0152 → hybrid $0.0173 per run. The delta is generation-token noise;
  hybrid's own overhead is ≈ $0 (BM25 is free in ES; one query embedding either way).

## Reliability flags (as predicted, now realized)

- **Small N (25):** every move is 1–3 entries — reported as counts.
- **Deterministic A/B:** confirmed (hybrid reproduced exactly across two runs).
- **candidate-k ≥ corpus size:** the lift is a small-corpus best case; revisit on a larger
  corpus where recall into the fusion actually varies.
- Labels assumed correct (Slice-2 reviewed).

## Artifacts

- `target/eval/baseline-pre-slice3.txt` — vector-only baseline.
- `target/eval/hybrid-slice3.txt` — hybrid run (same format; includes per-document breakdown).
(Both gitignored under `target/`.)

## Next (not done — awaiting review)

- If approved: commit Slice 3 (adapter hybrid + toggle + harness per-doc breakdown + this doc).
- The residual p5/p6 adjacent-page confusion points at chunking granularity as the next
  lever, not reranking. Hybrid's candidate-k is only meaningful on a larger corpus.
