## Context

The RAG knowledge assistant ([[rag-knowledge-assistant]]) is shipped: `RagIngestionService` owns `upload(filename, bytes, by) → PENDING`, `approve(id)` (chunk → per-chunk PII/relevance classify → embed → store), and `delete(id, by)` (cascade chunks/vectors + redaction audit). The PII gate is `ChunkClassifier` (Haiku 4.5, structured output), invoked inside `approve()`. Documents end `INDEXED` (≥1 clean chunk) or `QUARANTINED` (all chunks flagged); `RagChunkRepository.countByDocumentIdAndStatus(docId, status)` exposes the per-status counts.

This change adds an in-app Knowledge Base whose articles sync into that store. The defining constraints: **reuse the RAG paths, don't reimplement**; **no approval workflow** (unlike SOPs); **explicit on-demand sync only**. Latest migration is **V26** → KB is **V27**. Role gating in this codebase is matcher-based in `SecurityConfig` (no `@PreAuthorize` in code); ids are `BIGSERIAL`/Long; there is no rich-text editor dependency.

## Goals / Non-Goals

**Goals:**
- Owner/manager CRUD on KB articles; provider read-only — enforced server-side.
- Per-article role-based visibility (owners assign roles; providers see only shared articles; access shown as tags).
- A free, mature markdown editor with in-house (Claude) AI drafting.
- Content-hash change detection; sync is always a separate explicit action.
- Per-article and bulk sync that reuse the RAG ingest/PII/delete paths exactly.
- All-or-nothing PII rejection per the KB spec, with actionable error text.

**Non-Goals:**
- No approval or version history (that's the separate SOP feature); `updated_at` is the audit trail.
- No auto-sync on save, no scheduler, no import tooling, no paid editor / paid editor-AI add-on, no per-tenant scoping.

## Decisions

**D1 — Reuse `RagIngestionService` as-is; add KB orchestration, not a parallel pipeline.** KB sync calls the existing `delete` → `upload` → `approve`. The body (markdown) is passed as bytes with a `.md` filename so `DocumentTextExtractor` returns it verbatim and the RAG document's display name is the article title. *Alternative:* a new text-ingest method on `RagIngestionService` — rejected to keep the RAG service untouched and the reuse literal.

**D2 — All-or-nothing PII via a post-ingest check + rollback (not a new gate).** RAG's `approve()` indexes clean chunks and only quarantines flagged ones — it never rejects a whole document. KB requires whole-article rejection. After `approve()`, `KbSyncService` checks `countByDocumentIdAndStatus(ragDocId, QUARANTINED) > 0` (or document status ≠ `INDEXED`); if so it calls `RagIngestionService.delete(ragDocId)`, sets `sync_status = ERROR` with a PII message, and leaves `rag_doc_id` null. This reuses the exact gate and deletion code; the cost is embedding the clean chunks before discovering a flagged one (acceptable — KB articles are small and sync is manual). *Alternative:* a pre-ingest dry-run classify (no wasted embeds) — rejected for V1 because it would duplicate the classify loop that lives inside `approve()`.

**D3 — `rag_doc_id` is a nullable `BIGINT` with `ON DELETE SET NULL`.** It references `rag_document(id)`. If a RAG document is deleted out-of-band from `/rag/admin`, the KB row must survive (KB owns the lifecycle and can re-sync), so the FK nulls rather than cascades. `sync_status` is then stale-but-recoverable; a re-sync fixes it. *Alternative:* no FK at all — rejected; the FK keeps referential intent explicit and `SET NULL` avoids stranding.

**D4 — Role gating via HTTP-method matchers**, matching the codebase (no `@PreAuthorize` precedent): `GET /api/kb-articles/**` = any authenticated (PROVIDER included); `POST/PUT/DELETE /api/kb-articles/**`, `/api/kb-articles/**/sync*`, and `ai-draft` = OWNER + MANAGER. The coarse matcher is the floor; per-article read authorization is enforced in the service layer by `visible_roles` (see D8). The provider's read access means the `/kb` nav link and read-only page are visible to providers; edit/delete/sync/assign controls are **not rendered** for them (not merely disabled), and only their permitted articles are listed.

**D5 — Concurrency: a single in-memory lock.** `sync-all` guards with an `AtomicBoolean`; a second concurrent call returns 409. Sufficient for a single-instance deployment ([[salaryreview-no-staging-env]]); a distributed lock is unnecessary. Per-article sync is naturally serialized by the user action and needs no lock.

**D6 — `body` is plain markdown in a `<textarea>`.** No editor library exists and the spec forbids introducing one. Category is a free-text `VARCHAR` (the spec allows free text; simplest, matches the schema's other string columns). `content_hash` is SHA-256 hex of the body.

**D7 — UI split: `/kb` for authoring, a section in `/rag/admin` for sync.** Authoring (CRUD) lives on a dedicated `/kb` page; sync status + the bulk **Sync** button live in a "KB Articles" section inside `/rag/admin` (which already owns RAG admin concerns). Per-article sync is offered there too, de-emphasized. This matches the spec's recommended split and the existing nav.

**D8 — Per-article visibility model: `visible_roles` set, admin bypass for management.** Each article stores `visible_roles` (a JSONB array of role names — idiomatic per `SuspiciousTriage.signals_json`). Read authorization: a user may list/fetch an article iff their role ∈ `visible_roles`. OWNER and MANAGER additionally see **all** articles in the management list (with access tags) so they can administer and assign — the filter only constrains non-admin readers (today, PROVIDER). Default `visible_roles` on create = `{OWNER, MANAGER}` (safe: not exposed to providers until shared). *Alternatives:* a `kb_article_role` join table (more normalized, but heavier and the codebase already uses JSONB string-lists) — rejected for V1; a single "min role level" instead of a set — rejected, doesn't express "one or many roles." *Open:* whether MANAGER should itself be gated by `visible_roles` rather than treated as admin — current default treats MANAGER as a co-admin (sees all); easy to flip if the user wants managers gated.

**D9 — Editor: a free MIT markdown editor; AI drafting via our own Claude, not a paid editor add-on.** The body stays **markdown** (so RAG ingestion is unchanged — D1). Editor candidates, all free/MIT and markdown-capable: `@uiw/react-md-editor` (lightweight markdown source + live preview — least risk, keeps body as markdown), BlockNote (Notion-style WYSIWYG, exports markdown — nicest UX, but some exporters need a paid plan for closed-source), TipTap core (most powerful/extensible, but WYSIWYG ⇒ HTML/JSON, needs markdown serialization, and the ecosystem nudges toward paid). **Recommendation: `@uiw/react-md-editor`** for fit + minimal risk; final pick is the user's. AI generation ("draft/improve this article") is a `POST /api/kb-articles/ai-draft` endpoint reusing the existing `AnthropicClient` — paid editor-AI add-ons (TinyMCE/CKEditor/TipTap AI) run $1–3k+/yr and we already have a Claude integration. *Alternative:* a paid all-in-one (TinyMCE) — rejected on cost and because our content must stay markdown for RAG.

## Risks / Trade-offs

- **Wasted embeds on a flagged article (D2)** → Mitigation: KB articles are small and sync is manual/infrequent; the clean chunks are deleted immediately on rollback. Revisit with a dry-run classify if cost matters.
- **Stale `sync_status` after an out-of-band RAG delete (D3)** → Mitigation: `ON DELETE SET NULL` + a re-sync; the status badge will show the article as needing sync.
- **In-memory lock doesn't span instances (D5)** → Mitigation: single-instance deployment; documented assumption.
- **`content_hash` drift if hashing isn't stable** → Mitigation: hash the raw `body` bytes (UTF-8) deterministically; recompute fresh at sync time rather than trusting `sync_status`, per the spec.

## Migration Plan

1. Flyway `V27__kb_articles.sql` — the table, indexes (`category`, `sync_status`), and the `rag_doc_id` FK (`ON DELETE SET NULL`).
2. Backend: `KbArticle` entity (+ `SyncStatus` enum), repository, `KbArticleService` (CRUD + hashing/status), `KbSyncService` (per-article + bulk, reusing `RagIngestionService`), `KbArticleController`; `SecurityConfig` matchers.
3. Frontend: `/kb` page, KB section in `/rag/admin`, proxy routes, `api.ts`/types, nav link.
4. Ships without a flag (it's owner/manager UI; harmless when empty). Content is pasted in manually after deploy.

**Rollback:** drop the `/kb` nav link / endpoints; `kb_articles` is inert if unused. Any RAG documents created by sync are ordinary `rag_document` rows and removable from `/rag/admin`.

## Open Questions

- Should a KB article map to exactly one `rag_document` (current design) or be allowed to split into several? V1 assumes one-to-one (one article = one RAG document); revisit if articles grow large.
- Should `sync-all` surface a summary (counts of synced/error) beyond per-article status? V1 refreshes per-article status; a summary toast is a nice-to-have.
