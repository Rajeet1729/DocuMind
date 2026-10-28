# ADR 0002 — Offline, deterministic tests: real ES, no live OpenAI

- **Status:** accepted
- **Date:** 2026-10-28
- **Slice:** 1 — thin end-to-end

## Context

The slice touches two awkward dependencies for a test suite: **OpenAI** (a paid API that
needs a secret and a network, and whose output is non-deterministic) and
**Elasticsearch** (a stateful store whose real behaviour — index creation, cosine
scoring, JSON number round-tripping — is exactly what the `ChunkStore` adapter has to get
right). The suite has to run on CI, for every contributor, with no `OPENAI_API_KEY` and
no flaky network, and still be trustworthy.

## Options

1. **Hit live OpenAI + real ES.** Highest fidelity, but needs a funded key in CI, costs
   money per run, is non-deterministic (ranking/answer assertions flake), and fails with
   no network.
2. **Mock everything, including Elasticsearch.** Fast and hermetic, but a mocked vector
   store proves nothing about the adapter — the mapping and scoring bugs live precisely
   in the real ES behaviour we'd be stubbing out.
3. **Real ES via Testcontainers + deterministic stand-ins for the OpenAI models.** The
   one live dependency becomes Docker, not a paid API.

## Decision

Option 3.

- **Elasticsearch is real, via Testcontainers** (`TestcontainersConfiguration`, wired in
  with `@ServiceConnection`), security disabled to mirror `compose.yaml`.
- **The six OpenAI auto-configurations are excluded** (`spring.autoconfigure.exclude=…`)
  in every test that loads a context, so no key is read and no OpenAI endpoint is
  contacted.
- **Two kinds of stand-in, by what each test needs:**
  - the `ElasticsearchChunkStoreTest` supplies a **deterministic fake `EmbeddingModel`**
    (hashing-trick bag-of-words, L2-normalized) so cosine similarity tracks token overlap
    and the ranking is reproducible run-to-run — this test asserts real ranking;
  - the full-context `DocumindApplicationTests` imports `StubAiModelsConfiguration`, a
    trivial stub `ChatModel` + `EmbeddingModel`, because a context-load smoke test only
    needs the beans to exist and start, not to rank anything.
- Docker Compose is disabled in tests (`spring.docker.compose.enabled=false`) so
  Testcontainers is the only thing starting a store.

## Why

- **Deterministic, free, CI-friendly.** No secret, no network, no per-run cost; ranking
  assertions are stable because the embedder is a pure function.
- **Fidelity where it matters.** The adapter is tested against the actual ES scoring and
  metadata round-trip; the thing we most need to be right is not stubbed.
- **The stand-in matches the assertion.** A deterministic embedder only where ranking is
  asserted; a cheap stub where only startup is asserted. No test carries more machinery
  than its claim.

## Consequences

- **Tests need a running Docker daemon.** That is the deliberate trade for real-ES
  fidelity; documented in the checkpoint and the run instructions.
- **Each distinct `@SpringBootTest` context starts its own ES container**, so a full-suite
  run boots several back to back. On a modest machine the default 60s "started" log-wait
  flaked, so `TestcontainersConfiguration` pins a small fixed ES heap
  (`-Xms512m -Xmx512m`, mirroring `compose.yaml`) and extends the startup timeout to
  3 minutes. If a future slice adds many more context variants, revisit sharing a single
  container across contexts.
- **The exclusion list is coupled to Spring AI's package names.** If the OpenAI starter
  renames its auto-config classes, the list must be updated in lockstep (it lives in both
  the adapter test and the smoke test).
