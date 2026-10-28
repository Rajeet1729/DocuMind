# ADR 0001 — Wrap Spring AI behind domain ports (hexagonal)

- **Status:** accepted
- **Date:** 2026-10-28
- **Slice:** 1 — thin end-to-end (PDF → chunk → embed → store → search → cited answer)

## Context

Spring AI hands us ready-to-use collaborators: a `VectorStore` for the Elasticsearch
index, a `ChatClient` for the LLM, and a `*DocumentReader` for PDFs. The quickest path
is to inject those framework types straight into the application services and call them
where we need them.

But the domain of this slice is small and specific — a `Chunk` with provenance, a
`RetrievedChunk` with a score, an `Answer` with `Citation`s — and the project is
explicitly a learning/portfolio codebase where every file has to be defensible on a
whiteboard, with the LLM provider and vector store expected to change in later slices
(hybrid retrieval, reranking, a different model tier).

## Options

1. **Use Spring AI types directly in the services.** Fewer files, no mapping layer.
   The services depend on `VectorStore`/`ChatClient` and on Spring AI's `Document`
   metadata-map shape; tests either hit the real collaborators or mock framework types.
2. **Define domain ports and put Spring AI behind adapters.** The services depend only
   on our own interfaces; each framework boundary lives in exactly one adapter.

## Decision

Option 2. Inbound ports (`IngestDocumentUseCase`, `AskQuestionUseCase`) and outbound
ports (`DocumentChunker`, `ChunkStore`) are plain interfaces over domain types. The
Spring AI collaborators sit behind adapters:

- `adapter/out/elasticsearch/ElasticsearchChunkStore` implements `ChunkStore` over the
  `VectorStore`;
- `adapter/out/pdf/PdfDocumentChunker` implements `DocumentChunker`;
- the `ChatClient` is injected into `QuestionAnsweringService` directly, since answering
  is that service's own job rather than a separate port.

The `ChunkStore` port is the **single home of the provenance ⇄ vector-store-metadata
mapping**, applied identically on `save` and `search` so citations round-trip exactly,
and the embedding model never leaks past that adapter — the domain never sees an
embedding.

## Why

- **Testability without the paid/stateful world.** Services are unit-tested against
  plain fakes and a mocked `ChatClient`; only the adapters need a real Elasticsearch
  (see [ADR 0002](0002-offline-deterministic-tests.md)). This is the decision that makes
  the offline test strategy possible.
- **Provider independence for later slices.** Swapping the model tier, or adding hybrid
  retrieval and reranking, is a change inside an adapter — the services and domain do
  not move.
- **One place for the mapping.** Stable chunk id (`documentId + "#" + chunkIndex`) and
  the four provenance keys are defined once, so re-ingesting overwrites instead of
  duplicating and citations stay faithful.

## Consequences

- A small mapping layer and a handful of interfaces that each have **one** implementation
  today. The project guide flags "a pattern with one implementation" as a smell, so the
  justification is explicit: the pain it buys off is live-API tests and the provider
  churn already on the slice roadmap, not speculative generality.
- Contributors must keep framework types (`Document`, `VectorStore`, `ChatClient`) out
  of the `domain` and `application` packages; they belong in `adapter/**`.
