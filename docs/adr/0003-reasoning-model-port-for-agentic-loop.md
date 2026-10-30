# ADR 0003 — A narrow `ReasoningModel` port for the agentic loop's LLM calls

- **Status:** accepted
- **Date:** 2026-10-30
- **Slice:** 4 — agentic loop (self-correcting retrieval)
- **Relates to:** [ADR 0001](0001-hexagonal-ports-around-spring-ai.md) (which injected the
  `ChatClient` directly and deferred wrapping it), [ADR 0002](0002-offline-deterministic-tests.md)

## Context

Slice 4 adds a bounded retrieve → judge sufficiency → reformulate → retry loop
(`AgenticRetrieval`). That puts two new LLM calls **inside the retrieval path**: a
sufficiency gate (structured verdict + "what is missing") and a query reformulation.

ADR 0001 let `QuestionAnsweringService` inject Spring AI's `ChatClient` directly, because
answering was that service's own job and one call site did not justify a port. The loop
is a different situation: a second application-layer component with a *structured*,
multi-step LLM dependency whose control flow is the thing under test.

## Options

1. **Inject `ChatClient` into `AgenticRetrieval`.** No new interface. The loop's tests
   must mock Spring AI's fluent builder chain (`prompt().system().user().call()...`), and
   prompt wording, JSON parsing and control flow are tested together or not at all.
2. **A narrow outbound port for the loop's calls only** — `ReasoningModel` with
   `judgeSufficiency(question, context)` and `reformulate(question, missingInfo, tried)`,
   implemented by `adapter/out/llm/ChatClientReasoningModel`.
3. **A general LLM port that also absorbs answer generation.** Moves working Slice-1 code
   for no new capability.

## Decision

Option 2. `ReasoningModel` lives in `application.port.out` and owns its response shape
(nested `SufficiencyVerdict`). The adapter holds the prompts (public constants, logged
verbatim by the eval) and the reply parsing; an unreadable gate reply is `UNPARSEABLE`,
which the loop treats as "stop and use the context in hand." Answer generation stays on
the directly injected `ChatClient` — the port is introduced for the new need, not as a
speculative refactor.

## Why

- **Testability of the loop's control flow** (ADR 0002): with a scripted `ReasoningModel`
  the loop's rules — no gate after the final pass, accumulate-then-cap, ties to the
  original query, stop on no progress, the iteration cap — are pinned by offline,
  deterministic unit tests (`AgenticRetrievalTest`). Parsing is tested separately against
  a stub `ChatModel` (`ChatClientReasoningModelTest`).
- **Keeps the framework at the edge.** `AgenticRetrieval` depends only on two ports
  (`ChunkStore`, `ReasoningModel`); no Spring AI type enters the new application code.
- **Measurability.** The eval builds the adapter over a metered `ChatClient`, so reasoning
  cost is the API's reported usage, not an estimate, without touching production code.

## Consequences

- Two LLM access styles coexist: generation via `ChatClient` in the service, reasoning via
  the port. Migrating generation behind a port is a possible later cleanup, deliberately
  not done here (one change at a time).
- The reasoning calls run on the generation model (`gpt-6-luna`) at its default
  temperature: the model rejects `temperature=0` (verified against the live API), so the
  loop's non-determinism is measured across runs rather than dampened.
