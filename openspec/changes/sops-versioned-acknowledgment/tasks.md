## 1. Database

- [x] 1.1 `V28__sops.sql` — `sops` (BIGSERIAL id, title, category, audience, current_version_id BIGINT NULL → `sop_versions(id)` ON DELETE SET NULL, status, created_by, created_at, updated_at), `sop_versions` (id, sop_id → `sops(id)`, version_number, body, status, created_by, created_at; `UNIQUE(sop_id, version_number)`), `sop_acknowledgments` (id, sop_version_id → `sop_versions(id)`, user_id → `app_user(id)`, acknowledged_at; `UNIQUE(sop_version_id, user_id)`); indexes on `sop_versions.sop_id`, `sops.audience`, `sops.status`

## 2. Domain & repositories

- [x] 2.1 Enums `SopAudience` (`MANAGER`, `PROVIDER`, `BOTH`), `SopStatus` (`ACTIVE`, `ARCHIVED`), `SopVersionStatus` (`DRAFT`, `PUBLISHED`)
- [x] 2.2 Entities `Sop` (@PreUpdate `updated_at`; `currentVersion` ManyToOne nullable), `SopVersion`, `SopAcknowledgment`
- [x] 2.3 Repositories: `SopRepository` (filter by status; fetch with current version), `SopVersionRepository` (by sop, max version_number), `SopAcknowledgmentRepository` (`existsBySopVersionIdAndUserId`, find acked user ids for a version); add `AppUserRepository.findByRoleInAndActiveTrue(Collection<Role>)`

## 3. Services

- [x] 3.1 `SopService` authoring — create (SOP + v1 draft), update title/category/audience, add draft version (`max+1`), publish (set `PUBLISHED` + `current_version_id`; reject if already published), archive/unarchive
- [x] 3.2 `SopService` reads — `list(role)`: owner = all; manager = `MANAGER`/`BOTH`, provider = `PROVIDER`/`BOTH`, both filtered to `ACTIVE` + has published version, with current-version content and the caller's ack flag (audience evaluated fresh); single-fetch authorized the same way
- [x] 3.3 `SopService.acknowledge(id, principal)` — resolve `current_version_id` (reject if none); reject if caller's role ∉ audience; idempotent (no-op if `existsBySopVersionIdAndUserId`); else insert one ack row
- [x] 3.4 `SopRosterService.roster(id)` — every active user in the audience (`findByRoleInAndActiveTrue`) joined to acks on the current version → status + timestamp each

## 4. Web

- [x] 4.1 `SopController` — `POST /api/sops`, `PUT /api/sops/{id}`, `POST /api/sops/{id}/versions`, `POST /api/sops/{id}/versions/{versionId}/publish`, `POST /api/sops/{id}/archive` + `/unarchive`, `GET /api/sops`, `GET /api/sops/{id}/acknowledgment-status`, `POST /api/sops/{id}/acknowledge`; DTOs; map domain errors (already-published, out-of-audience, nothing-to-ack) to clear 4xx
- [x] 4.2 `SecurityConfig` ordered matchers: `GET /api/sops/*/acknowledgment-status` = OWNER; `POST /api/sops/*/acknowledge` = MANAGER/PROVIDER; `GET /api/sops/**` = authenticated; `/api/sops/**` (other) = OWNER

## 5. Frontend

- [x] 5.1 Same-origin proxy routes under `app/api/sops/*` (list; [id] put; [id]/versions; [id]/versions/[versionId]/publish; [id]/archive; [id]/unarchive; [id]/acknowledgment-status; [id]/acknowledge; create)
- [x] 5.2 `app/lib/api.ts` + `serverApi.ts` additions and `Sop`/`SopVersion`/`SopRosterEntry`/`SopAudience` types
- [x] 5.3 `/sops` shared page (manager/provider) — render the role-filtered API as-is: list with status badge / "I have read and agree to follow this SOP" button (armed only after content opened this session), content view, signed timestamp once acknowledged
- [x] 5.4 `/sops/admin` page (owner) — all SOPs incl. drafts/archived with audience; create form (title, category, audience, body via `@uiw/react-md-editor`); per-SOP detail: version history, new draft + publish with side-by-side current-vs-new compare, editable audience (warn on widening), acknowledgment roster
- [x] 5.5 Nav: `/sops/admin` in `AdminMenu` (owner) and a manager/provider-visible `/sops` entry (alongside the KB link on `/reports` and `/me`)

## 6. Tests & verification

- [x] 6.1 Unit (`SopService` authoring/publish): publish sets `current_version_id` + status; re-publishing a published version rejected; new draft is `max+1` and doesn't change current
- [x] 6.2 Unit (`SopService` reads/ack): audience filter (manager vs provider vs owner; drafts/archived excluded for non-owners); acknowledge idempotent; out-of-audience acknowledge rejected; nothing-to-ack rejected; republish flips the caller back to unacknowledged
- [x] 6.3 Unit (`SopRosterService`): roster lists exactly the audience's active users with correct ack flags/timestamps for the current version
- [x] 6.4 `MockMvc` (`SopController`): owner-only writes + roster (403 for manager/provider); acknowledge allowed for MANAGER/PROVIDER; draft-only SOP hidden from a non-owner list
- [x] 6.5 Frontend `tsc` + `eslint` + `next build` clean
- [ ] 6.6 Manual at `localhost:3000` as `olexandr.kara2`: create a provider SOP → publish → (as a provider) acknowledge → owner roster shows them acked; publish v2 → provider shows unacknowledged again; archive → provider no longer sees it. No Square calls.
