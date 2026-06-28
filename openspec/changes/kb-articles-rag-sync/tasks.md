## 1. Database

- [x] 1.1 `V27__kb_articles.sql` — `kb_articles` (BIGSERIAL id, title, category, body, visible_roles JSONB NOT NULL, content_hash, rag_doc_id BIGINT NULL → `rag_document(id)` ON DELETE SET NULL, last_synced_at, last_synced_by, sync_status, last_sync_error, created_by, created_at, updated_at); indexes on `category` and `sync_status`

## 2. Domain & repository

- [x] 2.1 `SyncStatus` enum (`NOT_SYNCED`, `SYNCED`, `CHANGED`, `ERROR`)
- [x] 2.2 `KbArticle` entity (@PreUpdate sets `updated_at`, mirroring `SettlementFeedback`; `visibleRoles` as `List<Role>`/`Set<Role>` via `@JdbcTypeCode(JSON)` like `SuspiciousTriage.signals_json`)
- [x] 2.3 `KbArticleRepository` — standard CRUD + `findBySyncStatusIn(...)` for bulk sync ordering

## 3. Services

- [x] 3.1 `KbArticleService` — CRUD; recompute SHA-256 `content_hash` from `body` on create/update; set `sync_status` to `CHANGED` (was `SYNCED`, hash differs) or `NOT_SYNCED` (never synced); never auto-sync. Default `visibleRoles` to `{OWNER, MANAGER}` on create; apply read filtering (non-admin sees only articles whose `visibleRoles` contains their role; OWNER/MANAGER see all) for list + single-fetch authorization. Delete retires `rag_doc_id` via `RagIngestionService.delete` before removing the row.
- [x] 3.4 `KbAiDraftService` — `draft(prompt, currentBody?)` via the existing `AnthropicClient` (reuse `AnthropicClientConfig`'s shared bean), returns markdown; no paid editor-AI add-on
- [x] 3.2 `KbSyncService.syncOne(id, by)` — recompute hash fresh; no-op if `SYNCED` + hash matches; skip blank body with a clear message; retire prior `rag_doc_id` via `RagIngestionService.delete`; `upload(title+".md", body, by)` → `approve`; if `countByDocumentIdAndStatus(QUARANTINED) > 0` or status ≠ INDEXED → delete + `sync_status = ERROR` + PII message; else record `rag_doc_id`/hash/`last_synced_*`/`SYNCED`, clear error
- [x] 3.3 `KbSyncService.syncAll(by)` — sequential over `NOT_SYNCED`/`CHANGED`/`ERROR`; `AtomicBoolean` guard → 409 (`SyncInProgressException`) on a concurrent run

## 4. Web

- [x] 4.1 `KbArticleController` — `GET`/`POST /api/kb-articles`, `GET`/`PUT`/`DELETE /api/kb-articles/{id}`, `POST /api/kb-articles/{id}/sync`, `POST /api/kb-articles/sync-all`, `POST /api/kb-articles/ai-draft`; DTOs (incl. `visibleRoles` in/out); pass the caller's role for read filtering; map `SyncInProgressException` → 409
- [x] 4.2 `SecurityConfig` — method matchers: `GET /api/kb-articles/**` any authenticated; `POST/PUT/DELETE /api/kb-articles/**` + sync + `ai-draft` = OWNER+MANAGER (per-article read still authorized in the service via `visibleRoles`)

## 5. Frontend

- [x] 5.0 Add the chosen markdown editor dependency (default `@uiw/react-md-editor`, MIT) to `frontend/package.json`
- [x] 5.1 Same-origin proxy routes under `app/api/kb-articles/*` (list/create, [id] get/put/delete, [id]/sync, sync-all, ai-draft)
- [x] 5.2 `app/lib/api.ts` additions + `KbArticle`/`SyncStatus`/`Role`/request types
- [x] 5.3 `/kb` page — list (title, category, status badge, access-role tags, last-synced); create/edit form (title, category, markdown editor, `visibleRoles` role-assignment control, "Draft with AI" button → `ai-draft`); OWNER/MANAGER see edit/delete/assign, PROVIDER read-only with only permitted articles and no edit/assign controls rendered
- [x] 5.4 KB section in `app/rag/admin/page.tsx` — article list with status badges + access-role tags, primary bulk **Sync** button (sync-all), de-emphasized per-article sync, inline error text, per-article progress during a run, list refresh on completion
- [x] 5.5 Nav: `/kb` link in `AdminMenu` (owner/manager) and a provider-visible read-only entry point

## 6. Tests & verification

- [x] 6.1 Unit (`KbArticleService`): edit changes hash → `CHANGED`; unchanged body keeps status; no embedding on save; delete calls `RagIngestionService.delete` when `rag_doc_id` set
- [x] 6.2 Unit (`KbSyncService`): flagged content → rollback (`delete` called) + `ERROR` with PII message, no `rag_doc_id` (faked classifier, no real embedding); clean content → `SYNCED`; blank body skipped; re-sync retires prior doc; `sync-all` second concurrent call → 409
- [x] 6.3 `MockMvc` (`KbArticleController`): PROVIDER `GET` 200 returns only permitted articles; PROVIDER fetch of an unshared article not returned; PROVIDER `POST`/`PUT`/`DELETE`/sync/`ai-draft` 403 (or transitively via `SecurityConfig`, matching the repo convention); 409 on concurrent sync-all
- [x] 6.6 Unit: `visibleRoles` defaults to `{OWNER, MANAGER}` on create; read filtering excludes a provider from an unshared article and includes a shared one
- [x] 6.4 Frontend `tsc` + `eslint` clean
- [ ] 6.5 Manual at `localhost:3000` as `olexandr.kara2`: create an article → sync from `/rag/admin` → ask the assistant a question answered from it → confirm a cited answer; edit it → status shows `CHANGED` → re-sync; delete → RAG doc gone. No Square calls.
