## 1. Dependencies & infrastructure

- [x] 1.1 Swap Postgres image to `pgvector/pgvector:pg16` in `docker-compose.yml` (no separate prod compose — single file covers both)
- [x] 1.2 Add Maven deps: Apache Tika (`tika-core` + `tika-parsers-standard-package`) for extraction (Anthropic SDK 2.40.1 already present). Spring AI dropped — hand-rolled per design D5 (confirmed).
- [x] 1.3 Add env vars `VOYAGE_API_KEY` and `RAG_ENABLED` (default `false`) to `docker-compose.yml` and `.env.example`

## 2. Database migrations (Flyway, forward-only — next is V22)

- [x] 2.1 `V22__enable_pgvector.sql` — `CREATE EXTENSION IF NOT EXISTS vector`
- [x] 2.2 `V23__rag_document.sql` — id, filename, source_type, status (PENDING/INDEXED/...), uploaded_by, timestamps
- [x] 2.3 `V24__rag_chunk.sql` — document_id (FK cascade), ordinal, text, char_start/end, content_sha256, status (INDEXED/QUARANTINED), quarantine_reason, `embedding vector(1024)`, HNSW index (`vector_cosine_ops`)
- [x] 2.4 `V25__rag_agent_config.sql` — version, system_prompt, model, temperature, k, distance_threshold, active flag; seed one default version
- [x] 2.5 `V26__rag_redaction_audit.sql` — document_id, deleted_by, deleted_at (survives chunk deletion)

## 3. Config & feature gating

- [x] 3.1 `RagProperties` binding `rag.*` (enabled, voyageApiKey) mirroring `AiTriageProperties`; `rag.*` wired in `application.yml`
- [x] 3.2 `VoyageClient` gated by `@ConditionalOnProperty(prefix = "rag", name = "enabled")`, failing fast at startup when `RAG_ENABLED=true` and `VOYAGE_API_KEY` is missing. Anthropic client bean shared across triage+rag via `@ConditionalOnExpression`.
- [x] 3.3 `SecurityConfig` rules: `/api/rag/admin/**` = OWNER; `/api/rag/**` (ask + feedback) = OWNER + MANAGER

## 4. Domain & repositories

- [x] 4.1 `RagDocument`, `RagChunk`, `RagAgentConfig`, `RagRedactionAudit` entities (+ status enums). `RagChunk` deliberately does NOT map the `vector` column (`ddl-auto: validate`); embedding handled via native SQL.
- [x] 4.2 Repositories incl. native top-k NN query (`ORDER BY embedding <=> :vec LIMIT :k`) filtered to `INDEXED` within a distance floor, + native `updateEmbedding` (cast to `vector`); `ChunkMatch` projection

## 5. Ingestion pipeline (order is a correctness constraint)

- [x] 5.1 `VoyageClient` — thin HTTPS embedding client mirroring `LangSmithClient` (no SDK); `voyage-3.5` @ 1024-dim, document/query input types, pgvector-literal helper
- [x] 5.2 `DocumentTextExtractor` (Tika) + `Chunker` (structure-aware, ~3000 chars ≈ ~750 tokens, ~15% overlap, real char offsets)
- [x] 5.3 `ChunkClassifier` — Claude Haiku 4.5, structured output `ChunkClassification`; fail-safe (quarantines on error/refusal). (Batch API deferred — see note)
- [x] 5.4 `RagIngestionService` wiring `extract(upload) → chunk → classify → quarantine-or-pass → embed → store`; unit test asserts no embed call for quarantined chunks
- [x] 5.5 Document delete path: cascade-remove chunks/vectors + write redaction audit row

## 6. Retrieval & answer

- [x] 6.1 `RagRetrievalService` — embed question (query input type), fetch top-k with distance floor, empty on no match
- [x] 6.2 `RagAnswerService` — retrieved chunks as native `document` blocks (`textSource`) with `citations(enabled)`, cached system prompt; call Claude Haiku 4.5; parse `TextBlock.citations()` → `CitationCharLocation.documentIndex` → `rag_document`; "say you don't know" on empty context
- [x] 6.3 Two LangSmith spans (rag-retrieval + rag-generation) correlated by a shared `rag_request_id` tag via existing `LangSmithTracer`; answer records `rag_agent_config` version + generation run id (for feedback)
- [x] 6.4 `RagConfigService` — read active config; create-new-version on update (one active at a time)

## 7. Controllers

- [x] 7.1 `RagAdminController` — upload (PENDING), list (with chunk/quarantine counts), approve (triggers ingestion), delete, config read + new-version
- [x] 7.2 `RagController` — `POST /api/rag/ask` and `POST /api/rag/ask/feedback` (ships graded LangSmith run; stateless — no answer table)

## 8. Frontend (Next.js App Router)

- [x] 8.1 Same-origin proxy route handlers under `app/api/rag/*` (ask, ask/feedback, admin documents GET/POST-multipart, [id] DELETE, [id]/approve, config GET/POST). Multipart upload forwards raw (the JSON-forcing helper is bypassed).
- [x] 8.2 `app/lib/api.ts` additions (ask/feedback/upload/list/approve/delete/config) + `RagAnswer`/`RagCitation`/`RagDocumentSummary`/`RagAgentConfigDto` types
- [x] 8.3 Admin page `app/rag/admin/page.tsx`: upload, docs list (status chip + chunk/quarantine counts), approve, delete, versioned config editor
- [x] 8.4 Chat page `app/rag/page.tsx`: ask, cited answer + sources, thumbs up/down. Non-streaming (spinner) — native citations need the full message, same call as triage dropping streaming.

## 9. Tests & verification

- [x] 9.1 Unit test: PII-flagged chunk is quarantined and never embedded (mock `VoyageClient`) — `RagIngestionServiceTest` (4 tests)
- [x] 9.2 Unit test: empty retrieval → "don't know" with no LLM call; hits → cited answer; LLM failure → 502 (spy `callClaude`) — `RagAnswerServiceTest` (3 tests)
- [x] 9.3 `RagControllerTest` (standalone MockMvc, 6 tests): feature-off → 404, ask happy/blank/502, feedback happy/blank. Role-gating 403 enforced by `SecurityConfig` matchers, covered transitively per the repo's existing convention (same as the triage controller test).
- [x] 9.4 Frontend `tsc --noEmit` clean and `eslint` clean on all new files
- [ ] 9.5 Manual verify at `localhost:3000` as `olexandr.kara2`: upload a sample SOP → approve → ask → cited answer + two-span LangSmith trace; confirm no Square calls are made. **(Requires real VOYAGE_API_KEY + ANTHROPIC_API_KEY and hits paid APIs — owner-run; not automatable.)**
