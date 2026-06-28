## Context

The app has accounts with roles (OWNER/MANAGER/PROVIDER) over a server-side session; authorization is matcher-based in `SecurityConfig` (no `@PreAuthorize` in code), and `AppUserPrincipal` carries `userId`/`role`. IDs are `BIGSERIAL`/Long. The KB feature ([[kb-articles-rag-sync]]) added a markdown editor (`@uiw/react-md-editor`) and the same-origin proxy patterns. SOPs are the heavier sibling of KB: owner-authored, versioned, approval-gated policy docs that staff must acknowledge per version. Latest migration is V27 (KB) → SOP is **V28**. Square is untouched (owner-authored content).

## Goals / Non-Goals

**Goals:**
- Owner authoring with immutable, numbered versions and an explicit publish step that makes a version live.
- Audience targeting (manager/provider/both) that gates both visibility and who must acknowledge.
- Per-version, per-user, write-once acknowledgment; a republish resets it automatically.
- An owner roster of who has/hasn't acknowledged the live version, scoped to the audience.
- History preserved by archiving — never hard-deleting anything with acknowledgments.

**Non-Goals:**
- No RAG/assistant sync (that's KB); no line-level diff library; no ack revocation; no reminders; no per-tenant scoping; the "viewed before acknowledge" rule is a frontend UX gate, not server-enforced.

## Decisions

**D1 — Three tables; versions immutable; acks keyed to the version.** `sops` (stable identity + `current_version_id` + `status`), `sop_versions` (immutable snapshots, `DRAFT`/`PUBLISHED`), `sop_acknowledgments` (write-once, `UNIQUE(sop_version_id, user_id)`). The republish-resets-acknowledgment behavior is **not special-cased** — it falls out of joining acknowledgments on `sop_version_id = sops.current_version_id`: after publishing a new version, prior-version acks simply don't match. This is the core modeling decision and it keeps acknowledgment correctness declarative.

**D2 — Visibility and "acknowledged?" always resolve against `current_version_id`, audience evaluated fresh.** `GET /api/sops` filters by `audience` per the caller's role on every request (no cached audience), returns only `ACTIVE` + has-published-version SOPs for managers/providers (owner sees all), and left-joins acks on the current version for the caller. Widening audience (e.g. `MANAGER → BOTH`) therefore exposes the SOP to the new role as unacknowledged with no extra work.

**D3 — Role gating via ordered HTTP-method matchers** (codebase style). Specific first: `GET /api/sops/*/acknowledgment-status` = OWNER; `POST /api/sops/*/acknowledge` = MANAGER/PROVIDER; `GET /api/sops/**` = any authenticated (service does audience filtering); `/api/sops/**` (all other methods — create/update/versions/publish/archive) = OWNER. The acknowledge endpoint allows MANAGER/PROVIDER coarsely; the **service** additionally enforces the caller's role ∈ the SOP's audience (a provider can't ack a manager-only SOP) — same shape as KB's `visible_roles` check.

**D4 — Archive, never delete.** `status ACTIVE|ARCHIVED` on `sops`; archived SOPs drop out of manager/provider lists but stay in owner views. No delete endpoints for SOPs or versions — acknowledgments must never be orphaned. (FKs use `RESTRICT`/`SET NULL`, not cascade.)

**D5 — Reuse the KB markdown editor; body is markdown.** `sop_versions.body` is markdown, edited with the KB feature's `@uiw/react-md-editor` (no new dependency). This makes SOP **depend on the KB feature** — SOP stacks on the KB branch (or rebases onto master once KB merges); migration is V28 regardless. *Alternative:* a richer editor — rejected; consistency with KB and zero new deps.

**D6 — Circular FK handled by insert ordering.** `sop_versions.sop_id → sops.id` and `sops.current_version_id → sop_versions.id` form a cycle. `current_version_id` is nullable; create flow is: insert `sops` (null current), insert `sop_versions` v1, leave current null until publish; publish updates `current_version_id`. `version_number` is guarded by `UNIQUE(sop_id, version_number)`.

**D7 — UI split: `/sops` (shared manager/provider) + `/sops/admin` (owner).** The staff page renders exactly what the role-filtered API returns (no client-side audience logic): list with status badge / "I have read and agree to follow this SOP" button, content opens on click, the button arms only after the content has been viewed this session, and a signed timestamp shows once acknowledged. The owner page lists all (incl. drafts/archived) with audience, a create form, and a per-SOP detail with version history, new-draft + publish (with a **side-by-side current-vs-new text compare** before confirming — no diff lib), an editable audience selector (warns that widening adds acknowledgers), and the roster. A `BOTH` SOP renders identically to managers and providers — no role-forked UI beyond list filtering.

## Risks / Trade-offs

- **Circular FK / insert ordering (D6)** → Mitigation: nullable `current_version_id`, set on publish; documented create flow; Hibernate saves the SOP, then the version, then updates the pointer.
- **`version_number` race on concurrent new-draft** → Mitigation: `UNIQUE(sop_id, version_number)` + owner-only, single-actor usage; retry on the rare conflict.
- **Audience-filter correctness is security-relevant** (a provider must not see a manager-only SOP) → Mitigation: enforced server-side in the service for both list and single-fetch and acknowledge; covered by tests. The UI filtering is cosmetic only.
- **Widening audience surfaces unacknowledged SOPs to a new role** — intended, but a UI warning prevents accidental exposure.

## Migration Plan

1. Flyway `V28__sops.sql` — `sops`, `sop_versions`, `sop_acknowledgments`; FKs (cycle via nullable `current_version_id`); `UNIQUE(sop_id, version_number)` and `UNIQUE(sop_version_id, user_id)`; indexes on `sop_versions.sop_id`, `sops.audience`, `sops.status`.
2. Backend: entities + enums, repositories (+ `AppUserRepository.findByRoleInAndActiveTrue`), `SopService` / `SopRosterService`, `SopController`, `SecurityConfig` matchers.
3. Frontend: `/sops` + `/sops/admin`, proxy routes, `api.ts`/`serverApi.ts`/types, nav links; reuse `@uiw/react-md-editor`.
4. No feature flag (owner/manager/provider UI, harmless when empty). The five planned provider SOPs are entered by hand after deploy.

**Rollback:** drop the nav links / endpoints; the tables are inert when unused. No external state.

## Open Questions

- Should the owner appear in a `BOTH` roster? Assumed **no** — owners author and don't acknowledge; roster = managers/providers only.
- Should `category` be a free-text string (assumed, matches the schema's other text columns) or an enum? Left as free text for V1.
- Side-by-side compare granularity — plain two-column text is assumed sufficient; revisit if owners ask for a real diff.
