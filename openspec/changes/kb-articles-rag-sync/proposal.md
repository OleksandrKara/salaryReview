## Why

Informational content the salon relies on — service menus, booking/communication scripts, cancellation & complaint scripts, FAQ — currently lives in Google Docs, outside the app and outside the knowledge assistant. It needs to be editable in-app by owners and managers and to feed the RAG assistant shipped in [[rag-knowledge-assistant]], so the assistant can answer from it. Unlike SOPs (a separate, approval-gated feature), this content is low-stakes and freely editable — no approval workflow.

This change adds an in-app Knowledge Base (KB) of articles with on-demand sync into the existing RAG/pgvector store, reusing the RAG ingestion/PII/deletion paths rather than introducing a parallel pipeline.

## What Changes

- **New `kb_articles` table** (Flyway V27 — current latest is V26): `id` (BIGSERIAL), `title`, `category`, `body` (markdown text), `visible_roles` (JSONB array of role names — who may read), `content_hash`, `rag_doc_id` (BIGINT → `rag_document.id`, nullable), `last_synced_at`, `last_synced_by`, `sync_status` (`NOT_SYNCED | SYNCED | CHANGED | ERROR`), `last_sync_error`, `created_by`, `created_at`, `updated_at`. No version-history table — `updated_at` is sufficient audit (mirrors `SettlementFeedback`).
- **Per-article role-based visibility:** owners/managers assign which roles may read an article (`visible_roles`, one or many). The management list shows each article's access as **role tags**. Non-admin readers (PROVIDER) see only articles whose `visible_roles` includes their role; OWNER/MANAGER see all for management. Default on create: `{OWNER, MANAGER}` (not exposed to providers until shared).
- **Rich editor + AI-assisted drafting:** the body editor is a free, mature markdown-capable library (chosen from a short list — see design D9), not a plain textarea. AI text generation ("draft/improve this article") is built in-house via a `POST /api/kb-articles/ai-draft` endpoint that reuses the existing Anthropic/Claude client — **not** a paid editor AI add-on (TinyMCE/CKEditor/TipTap AI run $1–3k+/yr).
- **CRUD endpoints** `GET/POST /api/kb-articles`, `GET/PUT/DELETE /api/kb-articles/{id}`:
  - On create/update: recompute `content_hash` from `body`; if it differs from the synced hash (or never synced) set `sync_status = CHANGED` (or `NOT_SYNCED` if never synced). **Never auto-sync on save.**
  - On delete: if `rag_doc_id` is set, delete that RAG document first (reuse `RagIngestionService.delete`) so no orphaned RAG docs remain, then delete the row.
- **`POST /api/kb-articles/{id}/sync`** — recompute the hash fresh; no-op if already `SYNCED` with a matching hash; otherwise retire any prior `rag_doc_id` (reuse `RagIngestionService.delete`), run the body through the **existing PII/relevance gate** with **all-or-nothing** semantics (any flagged chunk → don't sync, `sync_status = ERROR` with an actionable PII message), else ingest via the existing `upload → approve` path and record `rag_doc_id`, hash, `last_synced_*`, `sync_status = SYNCED`.
- **`POST /api/kb-articles/sync-all`** — sequentially sync every article in `NOT_SYNCED`/`CHANGED`/`ERROR`; reject a concurrent run with 409 (`sync already in progress`).
- **Role gating** (HTTP-method matchers in `SecurityConfig`, the codebase's existing style): `GET /api/kb-articles/**` = any authenticated; writes + sync + `ai-draft` = OWNER + MANAGER. On top of the coarse matcher, reads are **filtered/authorized by `visible_roles`** in the service layer (a PROVIDER can't fetch an article not shared with PROVIDER).
- **Frontend**: a dedicated **`/kb`** page (list + create/edit form with the markdown editor, role-assignment control, and a "Draft with AI" action; OWNER/MANAGER edit, PROVIDER read-only with no edit/delete/assign controls rendered and only their permitted articles shown), and a **"KB Articles" sync section inside `/rag/admin`** (status badges + access-role tags, a primary bulk **Sync** button → `sync-all`, a de-emphasized per-article sync, inline error display, per-article progress during a run, list refresh on completion).

## Capabilities

### New Capabilities
- `kb-articles-rag-sync`: An in-app, owner/manager-editable Knowledge Base of informational articles (provider read-only), with content-hash change detection and explicit on-demand sync into the existing RAG/pgvector store — reusing the RAG ingestion, PII/relevance gate, and deletion paths — with per-article and bulk sync, all-or-nothing PII rejection, and traceable sync status.

### Modified Capabilities
*(none — additive. The `rag-knowledge-assistant` capability is reused via its existing service API, not changed.)*

## Impact

- **Backend**: new `com.salonreview.kb` package (entity `KbArticle` + `SyncStatus`, repository, `KbArticleService`, `KbSyncService`, `KbArticleController` + an `ai-draft` endpoint reusing the existing `AnthropicClient`). Reuses `RagIngestionService` (`upload`/`approve`/`delete`), `ChunkClassifier`/the gate (via the ingest path), and `RagChunkRepository.countByDocumentIdAndStatus` for the all-or-nothing check. `visible_roles` authorization in the service layer. `SecurityConfig` gains method-specific `/api/kb-articles/**` matchers.
- **DB**: Flyway `V27__kb_articles.sql` — includes `visible_roles` (JSONB, idiomatic per `SuspiciousTriage.signals_json`). `rag_doc_id` is a nullable `BIGINT` reference to `rag_document(id)` with `ON DELETE SET NULL` (a RAG-side delete must not strand the row; KB owns the lifecycle).
- **Frontend**: new `/kb` route (list + form with the markdown editor, role-assignment control, AI-draft button), a KB section in `app/rag/admin/page.tsx`, same-origin proxy routes under `app/api/kb-articles/*`, `app/lib/api.ts` additions + `KbArticle`/`SyncStatus`/`Role` types, and a `/kb` nav link (visible to PROVIDER too, read-only, scoped to permitted articles).
- **Dependencies (frontend)**: one free, MIT-licensed markdown editor library (selected from a short list — see design D9). No paid editor or paid editor-AI add-on; AI drafting goes through our own Claude endpoint.
- **Square**: untouched — KB content is hand-authored in-app; no Square reads or writes.
- **Out of scope / Non-goals**: no approval/version-history workflow (that's the separate SOP feature); no auto-sync on save; no scheduler (on-demand only); no import tooling (the initial content is pasted in manually); no paid editor or paid editor-AI add-on; no per-tenant scoping (single corpus, per the RAG feature).
- **Verification**: backend unit tests — change detection sets `CHANGED`/`NOT_SYNCED` and never auto-syncs; sync rolls back and sets `ERROR` when the gate flags content (reusing a faked classifier so no embedding happens); delete removes the linked RAG doc; `sync-all` rejects a concurrent run (409). Backend `MockMvc` test — `/api/kb-articles/**` role gating (PROVIDER GET 200, PROVIDER POST/PUT/DELETE/sync 403). Frontend `tsc`/`eslint` clean. Manual check at `localhost:3000` as `olexandr.kara2`: create an article, sync it from `/rag/admin`, ask the assistant a question it answers from that article; confirm no Square calls are made.
