## Why

Salons need policy documents (SOPs) that staff must explicitly read and agree to — pricing rules, cancellation/fee handling, repair protocol, hygiene, scheduling. Unlike KB articles ([[kb-articles-rag-sync]], low-stakes, freely editable), SOPs are owner-authored, **versioned**, require **owner approval (publish)** before going live, and need a **per-version, per-user acknowledgment** ("I have read and agree to follow"). A republish must reset acknowledgment, and each SOP targets a specific **audience** (managers, providers, or both).

This adds the SOP capability: owner authoring/versioning/publishing, audience-scoped visibility, and an immutable acknowledgment trail with an owner roster of who has/hasn't acknowledged the live version.

## What Changes

- **Three new tables** (Flyway **V28** — stacks on the KB feature's V27):
  - `sops` — stable identity: `id` (BIGSERIAL), `title`, `category`, `audience` (`MANAGER | PROVIDER | BOTH`), `current_version_id` (FK → `sop_versions`, nullable = no live version yet), `status` (`ACTIVE | ARCHIVED`), `created_by`, `created_at`, `updated_at`.
  - `sop_versions` — immutable content snapshots: `id`, `sop_id` (FK), `version_number` (per-SOP, from 1), `body` (markdown), `status` (`DRAFT | PUBLISHED`), `created_by`, `created_at`. Never updated after insert.
  - `sop_acknowledgments` — write-once signatures: `id`, `sop_version_id` (FK — tied to the **version**, so a republish requires fresh ack), `user_id` (FK → `app_user`), `acknowledged_at`. UNIQUE(`sop_version_id`, `user_id`). Never edited or deleted.
- **Owner endpoints:** `POST /api/sops` (create SOP + first draft version), `PUT /api/sops/{id}` (title/category/audience — not content), `POST /api/sops/{id}/versions` (new draft), `POST /api/sops/{id}/versions/{versionId}/publish` (set version `PUBLISHED` + point `current_version_id` at it; rejects re-publishing an already-published version), `POST /api/sops/{id}/archive` + `/unarchive`, `GET /api/sops/{id}/acknowledgment-status` (audience roster: every user in the audience + their ack status/timestamp for the current version).
- **All-roles endpoint:** `GET /api/sops` — role-filtered by audience (manager → `MANAGER`/`BOTH`, provider → `PROVIDER`/`BOTH`, owner → all). For manager/provider: only `ACTIVE` SOPs **with a published version**, including the current version's content and whether the caller has acknowledged that exact version. Audience is always evaluated fresh (no caching), so widening audience immediately exposes the SOP to the newly-included role as unacknowledged.
- **Acknowledge:** `POST /api/sops/{id}/acknowledge` (MANAGER/PROVIDER) — resolve `current_version_id` (reject if none); idempotent no-op if already acknowledged; reject if the caller's role isn't in the SOP's audience; else insert one `sop_acknowledgments` row.
- **No hard deletes:** SOPs/versions are archived, never deleted — acknowledgments are never orphaned.
- **Role gating** via `SecurityConfig` method matchers (the codebase's existing style): owner-only writes + roster; acknowledge = MANAGER/PROVIDER; `GET /api/sops` = any authenticated (service filters by audience).
- **Frontend:** a shared **`/sops`** list+detail page for managers/providers (render what the API returns — no client-side audience logic; open content, then the "I have read and agree to follow this SOP" button; show acknowledged timestamp once signed), and an owner **`/sops/admin`** page (all SOPs incl. drafts/archived with audience; create form; per-SOP detail with version history, new-draft + publish with a side-by-side current-vs-new comparison before confirming, and the acknowledgment roster). Reuses the KB feature's `@uiw/react-md-editor` for the body editor.

## Capabilities

### New Capabilities
- `sops-versioned-acknowledgment`: Owner-authored, versioned, approval-gated SOPs with audience targeting (manager/provider/both) and an immutable per-version, per-user acknowledgment trail — including audience-scoped visibility, republish-resets-acknowledgment, an owner acknowledgment roster, and archive-not-delete history preservation.

### Modified Capabilities
*(none — additive. Reuses the KB markdown editor and the existing auth/role patterns; no existing requirement changes.)*

## Impact

- **Backend**: new `com.salonreview.sop` package (entities `Sop`/`SopVersion`/`SopAcknowledgment` + enums `SopAudience`/`SopStatus`/`SopVersionStatus`, repositories, `SopService` (authoring/versioning/publish/archive + audience-filtered reads + acknowledge), `SopRosterService`, `SopController`). New `AppUserRepository.findByRoleInAndActiveTrue(...)` for the roster. `SecurityConfig` gains `/api/sops/**` method matchers. Uses `AppUserPrincipal.getUserId()`/`getRole()`.
- **DB**: Flyway `V28__sops.sql` (three tables, FKs, the UNIQUE ack constraint, indexes on `sop_id`, `audience`, `status`).
- **Frontend**: `/sops` (manager/provider) and `/sops/admin` (owner) routes, same-origin proxy routes under `app/api/sops/*`, `app/lib/api.ts` + `serverApi.ts` additions and `Sop`/version/ack types, nav links (admin menu for owner; a provider/manager-visible entry like the KB link). Reuses `@uiw/react-md-editor`.
- **Depends on**: the KB feature ([[kb-articles-rag-sync]], PR open) for the editor dependency — SOP stacks on it (or rebases onto master once KB merges); migration is V28 either way.
- **Square**: untouched — SOP content is owner-authored; no Square reads/writes.
- **Out of scope / Non-goals**: no RAG/assistant sync (SOPs are read+acknowledge, not assistant content — that's KB's job); no line-level diff library (side-by-side text compare only); no hard delete; no acknowledgment revocation; no scheduled reminders; no per-tenant scoping.
- **Verification**: backend unit tests — publish points `current_version_id` + flips status and resets effective acknowledgment (acks keyed to version); acknowledge is idempotent and audience-gated (provider rejected on a manager-only SOP); audience filtering (manager/provider see only their audience + published + active); republishing an already-published version rejected; archive hides from manager/provider but keeps owner audit; roster lists exactly the audience with correct ack flags. Backend `MockMvc` — `/api/sops/**` role gating (owner-only writes/roster; acknowledge MANAGER/PROVIDER; drafts hidden from non-owners). Frontend `tsc`/`eslint`/`next build` clean. Manual at `localhost:3000` as `olexandr.kara2`: create a provider SOP → publish → acknowledge as a provider → roster shows them; publish v2 → provider flips back to unacknowledged. No Square calls.
