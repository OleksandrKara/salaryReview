## Why

Managers and owners repeatedly ask the same operational questions — the no-show policy, how a redo is handled, the commission tier for a senior stylist, how to comp a service for the owner's family — and the answers live scattered across Google Docs, Notion pages, and PDFs with the owner as the human index. This change lets them ask in natural language and get an answer **grounded in an uploaded document corpus, with citations**, so the knowledge survives the owner not being reachable.

It is feature #3 on the AI roadmap ([[salaryreview-ai-roadmap]]), chosen to reuse the production-AI muscles already built for suspicious-booking triage (Anthropic SDK, structured outputs, prompt caching, LangSmith tracing, feature-flag gating) while adding the one genuinely new subsystem: a vector retrieval pipeline.

## What Changes

- **New backend RAG module** under `com.salonreview.rag`, gated by a feature flag the same way `ai.triage` is. When off, no RAG beans register and the endpoints 404.
- **Vector store via pgvector** — the Postgres image becomes `pgvector/pgvector:pg16` and a Flyway migration enables the `vector` extension. Vectors live in the same DB as domain data (one backup, transactional deletes). New tables `rag_document`, `rag_chunk` (with `embedding vector(1024)`, HNSW index), `rag_agent_config`.
- **Embeddings via Voyage AI** (`voyage-3.5`, 1024-dim) — a new thin HTTPS client (no SDK), mirroring the hand-rolled `LangSmithClient` pattern. New env vars `VOYAGE_API_KEY`, `RAG_ENABLED`.
- **Ingestion pipeline** — upload (PDF / Google-Doc export / Markdown) → Apache Tika text extraction → structure-aware chunking (~512–1024 tokens, ~15% overlap) → **per-chunk PII/relevance gate (Claude Haiku 4.5, structured output) that runs BEFORE embedding** so quarantined PII never egresses to Voyage → embed kept chunks → store. Bulk classification uses the Batch API.
- **Retrieval + answer** — question is embedded (same Voyage model), top-k (k≈5–8) nearest chunks fetched by cosine distance with a distance floor, passed to Claude **Haiku 4.5 as native `document` content blocks with citations enabled**, and the cited answer returned. Grounded-only: "if the context doesn't contain the answer, say so."
- **Admin surface** — upload UI, ingested-documents view (chunk/quarantine counts, delete), and a DB-backed versioned `rag_agent_config` (system prompt, model, temperature, k, distance threshold) so the agent can be tuned without a deploy and every answer trace records the config version.
- **Manager/owner chat UI** (Next.js App Router) — ask a question, see a streamed cited answer, thumbs up/down feedback.
- **LangSmith observability** — each RAG call is a two-span trace (retrieval span: chunk ids + distances; generation span: prompt + cited answer), reusing the existing `LangSmithTracer`. Feedback ships as graded runs, building a RAG eval set from day one.
- **Retrieval plumbing is hand-rolled, no Spring AI** (revised during implementation — see design D5): Spring AI's GA targets Spring Boot 3.x while this app is on Boot 4.0.6, and its `PgVectorStore` fights the rich `rag_chunk` schema. Instead: a thin `VoyageClient` (HTTPS, mirroring `LangSmithClient`), pgvector via a JPA native NN query, Apache Tika (direct dep) for extraction, and a small in-house splitter. The answer call stays on the direct Anthropic SDK.

## Capabilities

### New Capabilities
- `rag-knowledge-assistant`: Manager/owner-facing natural-language Q&A grounded in an admin-curated document corpus, with native citations, an ingestion pipeline whose PII/relevance gate runs before embedding, a versioned agent config, post-hoc chunk traceability and deletion, and full LangSmith observability.

### Modified Capabilities
*(none — this is purely additive. New endpoints sit under a new `/api/rag/**` tree gated in `SecurityConfig`; no existing requirement changes.)*

## Impact

- **Backend**: new `com.salonreview.rag` package (~8–10 classes: `VoyageClient`, ingestion service, chunker, PII classifier, retrieval service, answer service, controllers, config service). New `SecurityConfig` rules — `/api/rag/admin/**` = OWNER; `/api/rag/ask` + feedback = OWNER + MANAGER. Reuses `LangSmithTracer`/`AiTriageProperties`-style config binding.
- **DB**: Flyway `V22__enable_pgvector.sql`, `V23__rag_document.sql`, `V24__rag_chunk.sql`, `V25__rag_agent_config.sql` (verified current latest is V21).
- **Dependencies (Maven)**: add Apache Tika (`tika-core` + `tika-parsers-standard-package`) for text extraction. Anthropic Java SDK already present (2.40.1). No Spring AI, no LangChain — Voyage and pgvector are hand-rolled (see design D5).
- **Docker**: Postgres image → `pgvector/pgvector:pg16`. New env vars in `docker-compose.yml` + `.env.example`: `VOYAGE_API_KEY`, `RAG_ENABLED` (default `false`, ships dark).
- **Frontend**: new admin upload/manage routes and a manager/owner chat route, with same-origin proxy route handlers under `app/api/rag/*`. New `RagAnswer` / citation types.
- **Square**: untouched. This feature reads no Square data and writes nothing — corpus is admin-uploaded documents only.
- **Out of scope / Non-goals (V1)**: no autonomous actions (answers only, never mutates payroll/bookings); no per-tenant document isolation (single corpus — deferred to the SaaS pivot [[salaryreview-square-saas-pivot]]); no regex/Presidio PII pre-pass (LLM-only gate in V1); no re-ranking / multi-hop retrieval; no fine-tuning; no prompt-editing beyond the config table; no provider-role exposure.
- **Verification**: backend unit test of the ingestion gate (a chunk flagged PII is quarantined, never embedded) and of retrieval assembly (top-k by distance with the floor) using a fake `EmbeddingModel`/`AnthropicClient`; backend integration test (`MockMvc`) of the `/api/rag/**` endpoints including role-gating denial; frontend tsc/build clean. Manual check at `localhost:3000` logged in as `olexandr.kara2`: upload a sample SOP, approve it, ask a question, see a cited answer and a two-span trace in LangSmith. No Square calls are made (the read-only Square integration is not touched).
