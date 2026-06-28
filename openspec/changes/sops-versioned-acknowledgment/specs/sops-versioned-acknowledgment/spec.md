## ADDED Requirements

### Requirement: Owner authoring and immutable versioning

An OWNER SHALL create a SOP together with its first draft version, add further draft versions, and edit a SOP's title/category/audience without changing content. `sop_versions` rows SHALL be immutable once created (content changes happen by adding a new version, never by editing an existing one). Only OWNER may author; managers and providers SHALL NOT create, edit, version, publish, or archive.

#### Scenario: Create a SOP with a first draft
- **WHEN** an OWNER `POST`s a title, category, audience, and body to `/api/sops`
- **THEN** one `sops` row (status `ACTIVE`, `current_version_id` null) and one `sop_versions` row (`version_number = 1`, status `DRAFT`) are created

#### Scenario: Add a new draft version
- **WHEN** an OWNER `POST`s to `/api/sops/{id}/versions`
- **THEN** a new `sop_versions` row is created with `version_number = max + 1`, status `DRAFT`, and `current_version_id` is unchanged

#### Scenario: Non-owner cannot author
- **WHEN** a MANAGER or PROVIDER calls any create/update/version/publish/archive endpoint
- **THEN** the system returns 403

### Requirement: Publishing makes a version live and resets acknowledgment

`POST /api/sops/{id}/versions/{versionId}/publish` SHALL set that version's status to `PUBLISHED` and point `sops.current_version_id` at it. Because acknowledgment is keyed to `sop_version_id` and visibility resolves against `current_version_id`, every user in the audience whose acknowledgment is against a prior version SHALL appear unacknowledged for the new live version. Re-publishing an already-published version SHALL be rejected.

#### Scenario: Publish sets the live version
- **WHEN** an OWNER publishes a draft version
- **THEN** that version's status is `PUBLISHED` and `current_version_id` references it

#### Scenario: Republish resets acknowledgment
- **WHEN** a user acknowledged version 2 and the OWNER then publishes version 3
- **THEN** that user is reported as not-acknowledged for the SOP's current version (their version-2 acknowledgment no longer satisfies the check), and their version-2 row is left untouched

#### Scenario: Cannot re-publish a published version
- **WHEN** an OWNER publishes a version whose status is already `PUBLISHED`
- **THEN** the system rejects it (no-op / clear error) and does not change `current_version_id`

### Requirement: Audience-scoped visibility

`GET /api/sops` SHALL return SOPs filtered by the caller's role against `audience`: a MANAGER sees `MANAGER`/`BOTH`, a PROVIDER sees `PROVIDER`/`BOTH`, an OWNER sees all. For managers/providers the result SHALL include only `ACTIVE` SOPs that have a published current version, each with that version's content and whether the caller has acknowledged that exact version. Audience SHALL be evaluated fresh on every request.

#### Scenario: Manager sees only manager/both SOPs
- **WHEN** a MANAGER lists SOPs
- **THEN** the result contains SOPs with audience `MANAGER` or `BOTH`, and none with audience `PROVIDER`

#### Scenario: Draft-only SOPs are hidden from non-owners
- **WHEN** a PROVIDER lists SOPs and a SOP has no published version
- **THEN** that SOP does not appear in the provider's result

#### Scenario: Widening audience exposes the SOP immediately
- **WHEN** an OWNER changes a SOP's audience from `MANAGER` to `BOTH`
- **THEN** providers now see it and, not having acknowledged the current version, see it as unacknowledged — with no new version required

### Requirement: Per-version acknowledgment, idempotent and audience-gated

`POST /api/sops/{id}/acknowledge` (MANAGER or PROVIDER) SHALL record a write-once acknowledgment of the SOP's `current_version_id` by the caller. It SHALL reject when there is no current version, be a no-op when the caller already acknowledged the current version, and reject when the caller's role is not in the SOP's audience. Acknowledgments SHALL never be edited or deleted.

#### Scenario: Provider acknowledges a provider SOP
- **WHEN** a PROVIDER acknowledges an `ACTIVE` SOP (audience `PROVIDER` or `BOTH`) with a published version
- **THEN** a `sop_acknowledgments` row is created for (current version, that user) with a timestamp

#### Scenario: Double-acknowledge is idempotent
- **WHEN** a user acknowledges a version they have already acknowledged
- **THEN** the system returns success without creating a second row

#### Scenario: Out-of-audience acknowledge is rejected
- **WHEN** a PROVIDER tries to acknowledge a SOP whose audience is `MANAGER`
- **THEN** the system rejects it (403/422) and writes no row

#### Scenario: Nothing to acknowledge
- **WHEN** a user acknowledges a SOP with no published version
- **THEN** the system rejects it with a clear message

### Requirement: Owner acknowledgment roster

`GET /api/sops/{id}/acknowledgment-status` (OWNER only) SHALL return every active user in the SOP's audience (managers for `MANAGER`, providers for `PROVIDER`, both for `BOTH`) with each user's acknowledgment status and timestamp for the current version.

#### Scenario: Roster reflects the audience and current version
- **WHEN** an OWNER requests the roster for a `BOTH` SOP
- **THEN** the result lists active managers and providers, each marked acknowledged (with timestamp) or not, for the current published version

### Requirement: Archive preserves history; no hard delete

A SOP SHALL be archivable and unarchivable by an OWNER. Archived SOPs SHALL be excluded from manager/provider lists but remain queryable in owner views. SOPs and versions that have acknowledgments SHALL NOT be hard-deleted — archiving is the only removal, so acknowledgments are never orphaned.

#### Scenario: Archived SOP hidden from staff, kept for owner
- **WHEN** an OWNER archives a SOP
- **THEN** managers/providers no longer see it, but the OWNER can still view it and its acknowledgment history

#### Scenario: Unarchive restores visibility
- **WHEN** an OWNER unarchives a SOP that has a published version
- **THEN** it reappears for its audience
