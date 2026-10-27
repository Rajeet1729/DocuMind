# Slice 1 — Checkpoint B (for review)

**Status:** paused for your review. Checkpoint A feedback applied and Step 3 complete
on branch `slice-1-thin-e2e`. New adapter test green (2/2) and the domain test still
green (5/5). The full-context `DocumindApplicationTests` is still deferred to Step 7.

**Resume at:** Step 4 — `PdfDocumentChunker` adapter (PagePdf → `TokenTextSplitter`) + test.

---

## Checkpoint A feedback — all four done

1. **Chat model changed.** I verified against OpenAI's live models page
   (`developers.openai.com/api/docs/models`) before writing anything. The whole line
   has moved past GPT-5.x — the current generation is **GPT-6**. `gpt-5.4-mini` is a
   generation behind. The current-gen *cheap* model (the real drop-in for
   `gpt-4o-mini`) is **`gpt-6-luna`** ($0.10/$0.50 per MTok, "high-volume" tier). That
   is what I wrote. Step up to `gpt-6.1-terra` ($1/$5) if you ever want more answer
   headroom — a one-line change. `text-embedding-3-small` left as-is.
2. **Validation guards kept** (structural only) — no change needed; they were already
   in `Provenance` / `Chunk` / `RetrievedChunk` / `Citation` / `Answer`.
3. **Maven wrapper regenerated** and pinned to **Maven 3.9.16** (latest 3.9.x, the
   Boot 4.x floor), wrapper plugin 3.3.4, `distributionType=only-script` (no jar to
   commit). `./mvnw -version` verified → Maven 3.9.16 on Java 21. `.mvn/wrapper/` is
   now committed.
4. **`spring-ai-vector-store-advisor` removed** from the pom.

---

## Step 3 — what landed

### `ElasticsearchChunkStore` (adapter/out/elasticsearch)

Implements the `ChunkStore` out-port over the Spring AI `VectorStore`. It is the single
home of the `Provenance` ⇄ ES-metadata mapping, applied identically on write and read:

- **Keys:** `documentId`, `filename`, `pageNumber`, `chunkIndex` (four constants).
- **`save`** maps each `Chunk` → `Document` with a **stable id** `documentId + "#" +
  chunkIndex`, so re-ingesting a document overwrites its chunks instead of duplicating.
- **`search`** issues `SearchRequest.query(q).topK(k)` and maps each `Document` back to
  `RetrievedChunk(Chunk + score)`. Numeric metadata is read defensively via `Number`
  (ES round-trips JSON numbers as Integer or Long); the `Provenance` record then
  re-validates the ranges.
- Depends only on `VectorStore` — embedding is the store's concern; the adapter never
  touches an embedding model.

### `ElasticsearchChunkStoreTest` — deterministic + offline (Fix #3)

- Real Elasticsearch via Testcontainers, but a **deterministic fake `EmbeddingModel`**
  stands in for OpenAI: a hashing-trick bag-of-words vector (each token bumps one of
  1536 dims; L2-normalized), so cosine similarity tracks token overlap and the ranking
  is reproducible run to run. `String.hashCode()` is spec-defined, so it's stable
  across JVMs.
- The six OpenAI auto-configurations are **excluded**, so the test needs **no
  `OPENAI_API_KEY`** and makes no network calls. The only moving part is the ES
  container.
- Two tests: (1) saves four chunks, forces an index refresh, searches, and asserts the
  most-relevant chunk ranks first, provenance round-trips **byte-for-byte**, scores are
  non-increasing and non-negative, and the France chunk out-ranks the Germany chunk;
  (2) `topK` is respected (1 → 1 result, 2 → 2).

---

## Three things the first real wiring surfaced (not in the Checkpoint A plan)

1. **GPT-6 generation** — see feedback item 1. Bigger jump than `gpt-5.4-mini`.
2. **Missing dependency: `spring-boot-elasticsearch`.** The Spring AI ES vector-store
   autoconfig consumes a Boot-provided `Rest5Client` bean and `ElasticsearchConnectionDetails`,
   but under Boot 4.x the Spring AI ES starter does **not** pull Boot's Elasticsearch
   module transitively. Without it there is no REST client bean (runtime) and no
   `@ServiceConnection` factory (tests) — the vector store could not be built at all.
   Added `org.springframework.boot:spring-boot-elasticsearch` (version from the Boot
   BOM). This also wires the docker-compose connection for `mvn spring-boot:run`.
3. **Test ES security.** The scaffold's `TestcontainersConfiguration` ran the container
   with security + `@Ssl` and no credentials → HTTP 401. I disabled security on the
   test container (`xpack.security.enabled=false`), mirroring `compose.yaml`, and
   dropped `@Ssl`. Also made the class `public` so adapter tests in other packages can
   import it.

---

## Verified facts (not assumed)

- `VectorStore.add(List<Document>)` does **not** refresh the ES index, so the test
  forces a refresh via the store's native `ElasticsearchClient` before searching.
- Spring AI's ES store returns the **raw cosine** as the score (non-negative here,
  since bag-of-words vectors are non-negative); a zero-overlap chunk scores exactly 0.
  The ranking assertions account for this.
- The ES store embeds via `embed(List<Document>, opts, strategy)` on write and
  `embed(String)` on query; the fake routes every embedding path through one
  deterministic function.

## Build order (where we are)

- [x] Step 1 — Domain records + four port interfaces
- [x] Step 2 — pom PDF reader dep; `application.properties`
- [x] Checkpoint A (reviewed, feedback applied)
- [x] **Step 3 — `ElasticsearchChunkStore` + Testcontainers test (fake `EmbeddingModel`)**
- [x] **>>> CHECKPOINT B (here)** — awaiting your OK
- [ ] Step 4 — `PdfDocumentChunker` adapter (PagePdf → `TokenTextSplitter`) + test
- [ ] Step 5 — `IngestionService` + `QuestionAnsweringService` + unit tests
- [ ] Step 6 — `IngestionController` + `QuestionController` + DTOs
- [ ] Step 7 — full path green end-to-end; two ADRs in `docs/adr/`

## How to run the adapter test

```bash
cd "/media/asus/HDD/Local disk E/DocuMind"
./mvnw -Dtest='ElasticsearchChunkStoreTest,DomainRecordsTest' test   # needs Docker, no API key
```
