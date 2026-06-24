## Context

The app already runs a production-AI feature (suspicious-booking triage) that calls Claude via the Anthropic Java SDK with structured outputs and prompt caching, traces every call to LangSmith through a hand-rolled async `LangSmithTracer`, and gates the whole module behind a `@ConditionalOnProperty(prefix = "ai.triage", name = "enabled")` flag. That feature is *generation only*.

RAG adds a *retrieval* half. The hard constraint that shapes everything: **Anthropic has no embeddings API** — `/v1/messages` is the entire surface — so embeddings come from a second vendor (Voyage AI, Anthropic's recommendation). Vectors are stored in the existing Postgres via pgvector. This design is purely additive; no existing capability changes. Latest Flyway migration is **V21**, so new migrations start at **V22**. The Square integration is untouched and stays read-only — the corpus is admin-uploaded documents, not Square data.

## Goals / Non-Goals

**Goals:**
- Manager/owner natural-language Q&A grounded in an admin-curated corpus, with native citations and a "say you don't know" floor.
- Self-host on the existing local + prod Docker setup with no new managed services (pgvector on the existing Postgres).
- A safety pipeline where the PII/relevance gate provably runs *before* any chunk text egresses to Voyage, plus post-hoc traceable deletion.
- Reuse the existing LangSmith tracer and feature-flag pattern; build a RAG eval set from feedback on day one.

**Non-Goals:**
- No autonomous actions — answers only; never mutates payroll, bookings, or Square.
- No per-tenant document isolation (single corpus; deferred to the SaaS pivot).
- No regex/Presidio PII pre-pass, no re-ranking, no multi-hop retrieval, no fine-tuning in V1.
- No prompt editing beyond the versioned config table; no provider-role access.

## Decisions

**D1 — Vector store: pgvector, not a dedicated vector DB.** Postgres 16 already runs; pgvector is an extension (`pgvector/pgvector:pg16` image + `CREATE EXTENSION vector`), so vectors co-locate with domain data — one backup, transactional deletes, FK cascade for deletion. *Alternatives:* Milvus/Qdrant/Weaviate (a separate service, justified only above ~10M vectors or write-heavy loads — a salon SOP corpus is thousands of chunks); Pinecone (managed — violates the no-new-infra constraint, another secret to hold). Index: **HNSW** with `vector_cosine_ops` (better recall/latency than IVFFlat, no training step).

**D2 — Embeddings: Voyage AI (`voyage-3.5`, 1024-dim).** Anthropic doesn't embed and recommends Voyage. A thin HTTPS client mirrors the existing `LangSmithClient` (no SDK needed for one endpoint). The 1024 dimension is a schema commitment — it fixes `rag_chunk.embedding vector(1024)` — and the same model must embed both documents and queries (mixing models breaks the metric space). *Alternatives:* local sentence-transformer (no new vendor, document text stays in-house, but lower recall and you operate a model) — kept as a future option; OpenAI embeddings (cheap, but a second non-Anthropic vendor with no narrative advantage over Voyage).

**D3 — Ingestion order is a correctness constraint, not a preference.** Because D2 sends chunk text off-box, the pipeline is strictly `extract → chunk → classify(PII/relevance) → quarantine-or-pass → embed → store`. The Haiku 4.5 classifier (structured output, exactly the triage pattern; Batch API for bulk) runs *before* the Voyage call so a chunk flagged PII never egresses. Chunking is structure-aware (split on Markdown headings/paragraphs), ~512–1024 tokens, ~15% overlap — large enough to stay self-explanatory, small enough to keep embeddings precise; overlap preserves boundary-straddling ideas.

**D4 — Answer path: Claude Haiku 4.5 with native Citations.** Retrieved chunks are passed as `document` content blocks with `citations: {enabled: true}`; the model returns answer spans tagged to source documents, which we map back to `rag_document` for display. *Consequence:* native citations are incompatible with `output_config.format`, so the answer call parses citation blocks rather than using structured output. Structured output is still used — on the ingestion classifier, a separate call. The stable system prompt is cached with `CacheControlEphemeral`; retrieved chunks (volatile) sit after the cache breakpoint. `temperature` is a valid knob on Haiku 4.5, so it lives in the config table — but marked model-dependent, because escalating to Opus 4.7+/Fable would 400 on `temperature` (steer those via `effort` instead).

**D5 — Hand-rolled, no Spring AI (revised at implementation time).** The original plan was Spring AI for the commodity plumbing (`PgVectorStore`, `EmbeddingModel`, Tika ETL). Implementation surfaced three problems: (1) Spring AI's GA targets Spring Boot 3.x and this app is on Boot **4.0.6** — a real version-resolution risk; (2) `PgVectorStore` owns its own `vector_store` table schema and fights the rich `rag_chunk` design here (status/quarantine/offsets/sha256/FK + a custom INDEXED-only NN query); (3) the codebase already hand-rolls its LLM HTTP clients (`LangSmithClient`), so a thin `VoyageClient` is the consistent choice and the framework would only own embedding calls + a splitter (~30 lines each). Decision: **`VoyageClient` = thin HTTPS client mirroring `LangSmithClient`; pgvector accessed via a JPA native nearest-neighbour query against `rag_chunk`; Apache Tika kept as a direct dependency for extraction; a small in-house structure-aware splitter.** The answer-generation call stays on the direct Anthropic SDK (already in use) for prompt caching, citations, and exact model strings. (Spring AI is not LangChain; the prior LangChain rejection was not the reason — Boot-4 compatibility and schema fit were.)

**D6 — Versioned config as data, not code.** Triage versions its prompt via a Java `PROMPT_VERSION` constant (PR-reviewed, deploy-to-change). RAG wants owner-tunable config, so it steps up to a DB-backed `rag_agent_config` (system prompt, model, temperature, k, distance threshold) with an incrementing version; every answer records its version, enabling LangSmith A/B exactly like triage prompt versions.

**D7 — Security & routing.** New `/api/rag/**` tree in `SecurityConfig`: `/api/rag/admin/**` = OWNER (upload, approve, delete, config); `/api/rag/ask` and `/api/rag/ask/feedback` = OWNER + MANAGER. Frontend reaches these only through same-origin proxy route handlers under `app/api/rag/*` (the browser never sees `BACKEND_URL`), matching the existing pattern.

## Risks / Trade-offs

- **Document text egresses to Voyage** → Mitigation: the PII/relevance gate (D3) runs before embedding, so flagged content never leaves; the OWNER pre-approval gate is the human backstop; deletion cascades (D-traceability). Revisit local embeddings if egress becomes unacceptable.
- **LLM PII gate has false negatives** → Mitigation: layered defense — human pre-approval (judgment) + LLM gate (scale) + traceable post-hoc deletion (reversal). Regex/Presidio pre-pass is a named V2 lever.
- **Short RAG system prompt may fall below the cacheable-prefix minimum** (4096 tokens on Haiku 4.5) and silently not cache → Mitigation: verify `cache_read_input_tokens` on real calls; accept no-cache for a small prompt (cost is minor at this volume).
- **pgvector outgrows OLTP DB** at scale (write-heavy, billions of vectors) → Mitigation: out of scope now; the `VectorStore` abstraction (D5) keeps a future swap to Qdrant localized.
- **Citations vs structured output is a one-way door on the answer path** → accepted deliberately (Q3): provenance matters more than a typed answer envelope; the ingestion classifier keeps structured output.
- **New external dependency (Voyage) + new key** → Mitigation: fail-fast startup check when `RAG_ENABLED=true` and key missing; ships dark (`RAG_ENABLED` default `false`).

## Migration Plan

1. Swap Postgres image to `pgvector/pgvector:pg16` in `docker-compose.yml` (and prod compose). Existing data is preserved — same PG 16 major, the image only adds the extension.
2. Flyway forward-only: `V22__enable_pgvector.sql` (`CREATE EXTENSION IF NOT EXISTS vector`), `V23__rag_document.sql`, `V24__rag_chunk.sql` (vector column + HNSW index), `V25__rag_agent_config.sql` (seed one default config version).
3. Add Maven deps (Spring AI BOM + pgvector store + Voyage embedding binding + Tika); Anthropic SDK already present.
4. Ship behind `RAG_ENABLED=false`. Enable locally, run the verification (upload SOP → approve → ask → cited answer + two-span LangSmith trace), then enable in prod.

**Rollback:** set `RAG_ENABLED=false` (beans don't register, endpoints 404). The pgvector extension and tables are inert when unused; no forward migration needs reverting. Reverting the Postgres image is unnecessary (the extension is backward-compatible) but possible since no domain table depends on `vector`.

## Open Questions

- Voyage model/dimension lock — `voyage-3.5` at 1024-dim is the default here; confirm before the `rag_chunk` schema lands (re-embedding to change dimension is a full corpus rebuild).
- Whether to seed the corpus from existing Notion/Drive exports manually or build a connector later (V1 assumes manual upload).
- Distance-floor value — needs tuning against a real SOP corpus; start conservative and adjust via the config table.
