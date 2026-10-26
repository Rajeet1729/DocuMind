# DocuMind — Project Guide for Claude Code

## What this is
Agentic document-intelligence service. A learning + portfolio project — I am an
experienced Java/Spring backend dev, new to Spring AI. I must understand and be
able to whiteboard every part, so teaching-while-building matters more than speed.

## Stack (pinned — do not "upgrade" without asking)
- Java 21, Spring Boot 4.1.x, Spring AI 2.0.0 (GA)
- Elasticsearch as vector store; OpenAI model + embeddings
- Spring AI 2.0 conventions: starter names are `spring-ai-starter-model-openai`
  and `spring-ai-starter-vector-store-elasticsearch`; versions come from the BOM
  (no version on starters); config props are flattened (e.g.
  `spring.ai.openai.chat.model`); NO milestone repo (everything is on Maven Central)

## Architecture
- Hexagonal / ports-and-adapters. Domain logic depends on interfaces (ports);
  the LLM provider, vector store, and PDF parser are adapters behind them.
- Config externalized (env vars). No hardcoded secrets or localhost. Stateless service.

## How we work (important)
- Plan before code: propose the design and tradeoffs, then WAIT for my approval.
  Do not write code in the same turn as a plan.
- One slice at a time. Do not build ahead of the current slice.
- Introduce a design pattern ONLY when justified — state the specific pain it solves.
  Favor KISS; a pattern with one implementation is a red flag.
- Every change ships with its tests.
- Record real decisions as short ADRs in docs/adr/ (context, options, choice, why).
- If I can't explain a file, stop and walk me through it before moving on.

## Slice roadmap (don't skip ahead)
1. Thin end-to-end: one PDF → chunk → embed → store → vector search → cited answer
2. Golden dataset (~50 Qs) + eval harness → baseline numbers
3. Hybrid retrieval (BM25 + vector + RRF) + reranking → measure the lift
4. Agentic loop: self-correcting retrieval + tool calling
5. Async ingestion on Kafka (retries + DLQ) + Redis semantic cache + Micrometer
6. Expose DocuMind as an MCP server
7. Testcontainers + CI eval gate → Dockerfile → deploy