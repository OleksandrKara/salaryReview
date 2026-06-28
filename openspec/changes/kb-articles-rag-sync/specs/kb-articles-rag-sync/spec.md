## ADDED Requirements

### Requirement: KB article CRUD with role-based access

Owners and managers SHALL be able to create, edit, and delete KB articles. Providers SHALL have read-only access (list and detail) and SHALL NOT be able to create, edit, delete, or sync. Access SHALL be enforced server-side via `SecurityConfig` matchers, independent of the UI.

#### Scenario: Owner/manager creates an article
- **WHEN** an OWNER or MANAGER `POST`s a title, category, and body to `/api/kb-articles`
- **THEN** a `kb_articles` row is created with `created_by` set and `sync_status = NOT_SYNCED`

#### Scenario: Provider can read (filtered) but not write
- **WHEN** a PROVIDER calls `GET /api/kb-articles`
- **THEN** the system returns 200 with only the articles whose `visible_roles` includes PROVIDER
- **WHEN** a PROVIDER calls `POST`, `PUT`, `DELETE`, `ai-draft`, or any sync endpoint
- **THEN** the system returns 403

### Requirement: Per-article role-based visibility

Each article SHALL carry a set of `visible_roles` (one or more roles) that owners and managers assign on create/edit. A reader SHALL only be able to list or fetch an article whose `visible_roles` includes their role; OWNER and MANAGER SHALL see all articles for management regardless, with each article's `visible_roles` surfaced as access tags. New articles SHALL default to `{OWNER, MANAGER}` (not exposed to providers until explicitly shared).

#### Scenario: Owner assigns roles and sees access tags
- **WHEN** an OWNER sets an article's `visible_roles` to include PROVIDER
- **THEN** the article is saved with that set and the management list shows its access as role tags

#### Scenario: Provider cannot fetch an unshared article
- **WHEN** a PROVIDER requests `GET /api/kb-articles/{id}` for an article whose `visible_roles` does not include PROVIDER
- **THEN** the system does not return the article (404/403), even though the coarse route matcher permits GET

#### Scenario: Default visibility excludes providers
- **WHEN** an OWNER creates an article without specifying roles
- **THEN** `visible_roles` defaults to `{OWNER, MANAGER}` and providers do not see it until it is shared

### Requirement: AI-assisted drafting via the in-house Claude endpoint

Owners and managers SHALL be able to generate or improve article body text with AI through `POST /api/kb-articles/ai-draft`, which calls the existing Anthropic/Claude client. No paid third-party editor-AI add-on SHALL be used.

#### Scenario: Owner drafts with AI
- **WHEN** an OWNER submits a prompt (and optionally the current body) to `ai-draft`
- **THEN** the system returns Claude-generated markdown the owner can insert/edit before saving

#### Scenario: Provider cannot use AI drafting
- **WHEN** a PROVIDER calls `ai-draft`
- **THEN** the system returns 403

### Requirement: Change detection without auto-sync

On create or update the system SHALL recompute `content_hash` from `body` and SHALL NOT sync to RAG as a side effect. If the article was previously `SYNCED` and the new hash differs from the synced hash, `sync_status` SHALL become `CHANGED`; if it was never synced, it SHALL be `NOT_SYNCED`.

#### Scenario: Editing a synced article marks it changed
- **WHEN** an OWNER edits the body of an article whose `sync_status` is `SYNCED`, changing its hash
- **THEN** `sync_status` becomes `CHANGED` and no embedding or RAG call is made on save

#### Scenario: Editing without changing the body keeps status
- **WHEN** an OWNER saves an article whose body (hash) is unchanged
- **THEN** `sync_status` is unchanged

### Requirement: Per-article sync with all-or-nothing PII rejection

`POST /api/kb-articles/{id}/sync` SHALL recompute the hash fresh, no-op when the article is already `SYNCED` with a matching hash, retire any existing `rag_doc_id` first (reusing the RAG deletion path), and run the body through the existing RAG PII/relevance gate. If any part of the content is flagged, the article SHALL NOT be synced: `sync_status = ERROR` with an actionable message naming PII quarantine, and `rag_doc_id` SHALL remain null. Otherwise the body SHALL be ingested through the existing RAG ingest path and `content_hash`, `rag_doc_id`, `last_synced_at`, `last_synced_by`, and `sync_status = SYNCED` recorded with `last_sync_error` cleared.

#### Scenario: Clean article syncs
- **WHEN** an OWNER syncs an article whose content passes the gate
- **THEN** it is ingested into the RAG store, `rag_doc_id` is set, `sync_status = SYNCED`, and the assistant can retrieve it

#### Scenario: Flagged article is rejected as a whole
- **WHEN** the PII/relevance gate flags any chunk of the body
- **THEN** nothing is left in the RAG store for this article, `sync_status = ERROR`, and `last_sync_error` states that PII quarantine flagged the content

#### Scenario: Re-sync retires the prior RAG document
- **WHEN** an OWNER syncs an article that already has a `rag_doc_id`
- **THEN** the previous RAG document is deleted before the new one is created (no orphaned RAG document remains)

#### Scenario: Empty body is not synced
- **WHEN** an OWNER syncs an article whose body is empty or blank
- **THEN** the article is skipped with a clear message and nothing is embedded

### Requirement: Bulk sync, sequential and non-concurrent

`POST /api/kb-articles/sync-all` SHALL process every article in `NOT_SYNCED`, `CHANGED`, or `ERROR` sequentially, applying the per-article sync logic so a failure is attributable to one article. A second `sync-all` started while one is running SHALL be rejected with HTTP 409.

#### Scenario: Bulk sync processes eligible articles
- **WHEN** an OWNER triggers `sync-all` with a mix of `CHANGED`, `ERROR`, and already-`SYNCED` articles
- **THEN** only the non-`SYNCED` articles are processed, one at a time, each ending `SYNCED` or `ERROR`

#### Scenario: Concurrent bulk sync is rejected
- **WHEN** a `sync-all` is already in progress and another is triggered
- **THEN** the second request returns 409 with a "sync already in progress" message

### Requirement: Delete cleans up the linked RAG document

Deleting a KB article that has a `rag_doc_id` SHALL delete that RAG document (reusing the RAG deletion path) before removing the `kb_articles` row, so no orphaned RAG document remains.

#### Scenario: Delete removes the RAG document
- **WHEN** an OWNER deletes a synced article
- **THEN** its RAG document (and chunks/vectors) are removed and the `kb_articles` row is deleted
