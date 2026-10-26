# Slice 1 — Checkpoint A (for review)

**Status:** paused for your review. Steps 1–2 complete and committed on branch
`slice-1-thin-e2e`. Domain test green (5/5). Nothing below Checkpoint A has been
built yet.

**Resume at:** Step 3 — `ElasticsearchChunkStore` adapter + its Testcontainers test
with a deterministic fake `EmbeddingModel` (Fix #3).

---

## How to resume tomorrow

```bash
cd "/media/asus/HDD/Local disk E/DocuMind"
git switch slice-1-thin-e2e
git log --oneline -3
```

Expected log:

```
8477f6b build(slice-1): add PDF reader dep; OpenAI + ES vector store config
4b66e24 feat(domain): slice-1 domain records and port interfaces
09c51c1 chore: initial Spring Boot 4.1 / Spring AI 2.0 scaffold
```

Then tell me "OK, continue" (plus your calls on the open decisions at the bottom)
and I start Step 3.

---

## Build order (where we are)

- [x] **Step 1** — Domain records + four port interfaces (no implementations)
- [x] **Step 2** — pom PDF reader dep; `application.properties`; compose confirmed
- [x] **>>> CHECKPOINT A (here)** — awaiting your OK
- [ ] **Step 3** — `ElasticsearchChunkStore` adapter + Testcontainers test (fake `EmbeddingModel`)
- [ ] **>>> CHECKPOINT B** — review adapter + test, await OK
- [ ] **Step 4** — `PdfDocumentChunker` adapter (PagePdf → TokenTextSplitter) + test
- [ ] **Step 5** — `IngestionService` + `QuestionAnsweringService` + unit tests
- [ ] **Step 6** — `IngestionController` + `QuestionController` + DTOs
- [ ] **Step 7** — full path green end-to-end; two ADRs in `docs/adr/`

---

## Package tree (`io.github.agentic.documind`)

```
main/java/io/github/agentic/documind
├─ DocumindApplication.java
├─ domain/                          ← pure Java 21, zero Spring / Spring AI imports
│   ├─ Provenance.java              (documentId, filename, pageNumber[1-based], chunkIndex)
│   ├─ Chunk.java                   (text + Provenance)
│   ├─ RetrievedChunk.java          (Chunk + score)
│   ├─ Citation.java                (filename, pageNumber, snippet)
│   └─ Answer.java                  (text + List<Citation>, defensively copied)
└─ application/port/
    ├─ in/   IngestDocumentUseCase.java   (+ nested IngestCommand / IngestResult)
    │        AskQuestionUseCase.java
    └─ out/  DocumentChunker.java
             ChunkStore.java

test/java/io/github/agentic/documind
└─ domain/DomainRecordsTest.java    (5 tests, green)
```

Adapters (`adapter/in/web`, `adapter/out/...`) and services
(`application/service`) are intentionally **not** created yet — Steps 3–6.

---

## The records (behaviour only; accessors omitted)

- **`Provenance`** — compact constructor rejects blank `documentId`/`filename`,
  `pageNumber < 1`, `chunkIndex < 0`. Citation backbone, so it's guarded.
- **`Chunk`** — rejects null `text`/`provenance`. Otherwise plain.
- **`RetrievedChunk`** — rejects null `chunk`; `score` documented "higher = more
  relevant," scale defined by the store.
- **`Citation`** — rejects blank `filename`, `pageNumber < 1`, null `snippet`.
- **`Answer`** — `citations = List.copyOf(...)`: truly immutable and null-safe.

## The ports

**Inbound**
- `IngestDocumentUseCase` → `IngestResult ingest(IngestCommand)`, where
  `IngestCommand(filename, byte[] content)` and `IngestResult(documentId,
  chunkCount)` are nested on the port.
- `AskQuestionUseCase` → `Answer ask(String question)`. Top-K is a service-config
  concern, not a port parameter.

**Outbound**
- `DocumentChunker` → `List<Chunk> chunk(String documentId, String filename,
  byte[] content)`. Caller mints the id; adapter assembles full provenance.
- `ChunkStore` → `void save(List<Chunk>)` + `List<RetrievedChunk> search(String
  query, int topK)`. Single home of the Chunk-provenance ⇄ ES-metadata mapping,
  applied identically on write and read.

## `application.properties`

```properties
spring.application.name=documind

# === OpenAI (spring-ai-starter-model-openai) ===
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.model=gpt-4o-mini
spring.ai.openai.embedding.model=text-embedding-3-small   # flat 2.x prop, verified

# === Elasticsearch vector store ===
spring.ai.vectorstore.elasticsearch.initialize-schema=true   # default flipped to false in 2.x
spring.ai.vectorstore.elasticsearch.index-name=documind-chunks
spring.ai.vectorstore.elasticsearch.dimensions=1536
spring.ai.vectorstore.elasticsearch.similarity=cosine
```

---

## Verified facts (not assumed)

1. **Fix #1 confirmed.** The Spring AI 2.0.x OpenAI embeddings reference documents
   `spring.ai.openai.embedding.model` (flat) for the `spring-ai-starter-model-openai`
   starter. `.options.model` is not the configured form. No conflict with your fix.
2. **`initialize-schema` default is now `false` in Spring AI 2.x** (breaking change
   from 1.x) — set to `true` or the index is never created.
3. **PDF reader** resolves to `org.springframework.ai:spring-ai-pdf-document-reader:2.0.1`
   (version from the BOM, no explicit version in pom); brings Apache PDFBox 3.0.7.
4. **ES vector store `dimensions` default is already 1536**, matching
   text-embedding-3-small; set explicitly anyway so the contract is visible.

## Design choices I made (you didn't specify these)

- Nested `IngestCommand` / `IngestResult` on the inbound port (keeps the domain to
  the five listed records).
- `documentId` supplied by the caller to `DocumentChunker` (service mints identity;
  chunker — the only place that knows pages/indices — assembles provenance).
- Chat model `gpt-4o-mini`; index name `documind-chunks`; explicit `dimensions=1536`.
- Light validation added to `Provenance` / `Citation` / `Answer`.

---

## Open decisions awaiting your call

1. **Validation in value records** — keep the light guards on `Provenance` /
   `Citation` / `Answer`, or strip them back to plain records?
2. **Maven wrapper** — `./mvnw` is broken: `.mvn/wrapper/` was never generated, so
   the wrapper can't run. I'm using **system Maven 3.8.7 / Java 21**. Boot 4.x's
   documented minimum is Maven **3.9+**; builds work so far. Want me to regenerate
   `.mvn/wrapper/` (pinned to 3.9+), or will you install 3.9+ locally?
3. **`spring-ai-vector-store-advisor`** is still in the pom from the scaffold.
   Unused this slice (QuestionAnswerAdvisor is out of scope). Leave it, or remove
   it for a clean classpath?

## Notes carried into later steps

- **`DocumindApplicationTests.contextLoads`** is a full `@SpringBootTest` needing
  Docker + a real `OPENAI_API_KEY`. Not run yet. The deterministic test story
  (Fix #3: fake `EmbeddingModel`, real-OpenAI check opt-in) lands in Step 3, and
  the full-context test gets sorted when making the path green in Step 7.
- To run the app later you'll need `export OPENAI_API_KEY=...` in the shell; Boot's
  docker-compose integration brings Elasticsearch up on `mvn spring-boot:run`.
