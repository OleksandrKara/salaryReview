## ADDED Requirements

### Requirement: Every business is a first-class row with its own identity
The system SHALL persist each business as a row in a `business` table (`id`, `name`, `short_code`
UNIQUE, `timezone`, `active`). The existing salon SHALL be migrated as the first `business` row with
no change to any URL, login, or reported figure visible to its users.

#### Scenario: Existing salon becomes Business A with zero visible change
- **WHEN** the migration that creates `business` and backfills the existing salon's row runs
- **THEN** the existing owner (`olexandr.kara2`) can still log in at the same URL, and
  `/api/settlements/preview`, `/reports`, `/me`, and `/owner/overview` return numerically identical
  output to before the migration for the same historical months

### Requirement: A user belongs to a business through an explicit membership, not implicitly
The system SHALL persist business membership as `business_membership(business_id, user_id, role,
UNIQUE(business_id, user_id))`, separate from the `app_user` login row. A user's active business for
a session SHALL be resolved from their membership row(s) at login.

#### Scenario: Single-membership user sees no business-selection UI
- **WHEN** a user with exactly one `business_membership` row logs in
- **THEN** their session is scoped to that business automatically, and no business switcher or
  selection prompt is shown anywhere in the UI

#### Scenario: A user with zero membership rows cannot authenticate into business-scoped areas
- **WHEN** a user with zero `business_membership` rows attempts to access any business-scoped
  endpoint (i.e. anything other than the platform-admin `/api/platform/**` paths)
- **THEN** the request is rejected (403), not silently defaulted to any business

### Requirement: Every data access is scoped to the requester's current business
The system SHALL resolve the current business from the authenticated session on every request via a
request-scoped `CurrentBusinessContext`, and SHALL apply that scope through both an explicit
`business_id` predicate in repository queries and a Hibernate session-level filter, such that a
request authenticated to Business A can never read, modify, or delete a row owned by Business B.

#### Scenario: Cross-tenant read is blocked
- **WHEN** a user authenticated as an OWNER of Business A requests a settlement, provider, user, or
  suspicious-booking resource that belongs to Business B (by id or by any list/search endpoint)
- **THEN** the response is 404 Not Found (not 200 with Business B's data, and not 403 revealing the
  resource exists)

#### Scenario: Cross-tenant write is blocked
- **WHEN** a user authenticated to Business A submits a create/update/delete request whose payload or
  path references a Business-B-owned entity (e.g. a `provider_id` or `tier_grant` belonging to
  Business B)
- **THEN** the request is rejected and no row belonging to Business B is modified

#### Scenario: Two businesses' identically-shaped data never collides
- **WHEN** Business A and Business B each have a pay period for "2026-08 FIRST", a provider named
  "Anna", and an `app_user` with username "manager"
- **THEN** each business's rows resolve independently under their respective `business_id` scope with
  no unique-constraint collision and no cross-business query ever returning the other business's row

### Requirement: Commission and cash-calculation configuration is per-business, not a global singleton
The system SHALL persist commission/cash-calculation configuration (`base_commission_rate`,
`tier_commission_rate`, `tier_service_threshold`, `service_price_cutoff`, `card_tip_fee_rate`) as one
row per business, resolved via `CurrentBusinessContext`, replacing the prior single global row. The
underlying commission calculation logic SHALL be unchanged for Business A.

#### Scenario: Business A's commission numbers are identical after the config table stops being a singleton
- **WHEN** the `salon_config` table is migrated from a single `CHECK (id = 1)` row to one row per
  business
- **THEN** Business A's settlement calculations for any previously-computed month remain byte-for-byte
  identical

#### Scenario: Business B can run different commission parameters without any code change
- **WHEN** Business B's `salon_config` row is set with different rate/threshold/cutoff values than
  Business A's
- **THEN** Business B's settlements are computed correctly under its own parameters using the same
  `TierCommissionEngine` code path, with no conditional branch on which business is being computed

### Requirement: Each business has its own isolated Square connection
The system SHALL persist Square credentials (access token, location id, environment) as one encrypted
row per business in `square_connection`, and SHALL construct a separate Square API client per
business, so that no two businesses ever share a Square client instance, response cache, or outbound
rate-limit throttle.

#### Scenario: Business A's Square sync is unaffected by Business B's Square connection
- **WHEN** Business B connects its own Square account
- **THEN** Business A's scheduled sync jobs, manual sync button, and cached Square reads continue to
  operate against only Business A's Square account, with no shared cache entry or throttle contention
  between the two

#### Scenario: A Square webhook event is routed to the correct business
- **WHEN** Square sends a `payment.updated` webhook notification
- **THEN** the system verifies the notification's signature against the specific business's stored
  webhook signature key (resolved from the request path) before processing it, and never processes a
  webhook whose signature doesn't match the business it claims to be for

### Requirement: Background/scheduled jobs process every connected business, not an implicit single one
Every scheduled job that reads Square data or sends business-scoped notifications SHALL iterate all
businesses with an active relevant connection (Square, Twilio) and execute its logic once per business
under that business's `CurrentBusinessContext`, with per-business distributed-lock keys so one
business's job run cannot block another's.

#### Scenario: Revenue snapshot job runs independently per business
- **WHEN** the nightly revenue snapshot job fires with two connected businesses
- **THEN** each business's revenue snapshot is computed from its own Square data and its own timezone,
  and a failure or delay processing one business does not prevent or delay the other's snapshot

### Requirement: Feature availability is configurable per business without deleting the underlying capability
The system SHALL persist per-business feature enablement (`business_feature(business_id, feature_key,
enabled)`) for optional subsystems (SMS automation, Telegram notifications, RAG assistant, marketing
analytics), defaulting a newly onboarded business to having only core features (commission, Square
sync, settlements, users) enabled.

#### Scenario: A newly onboarded business has a simple, uncluttered UI
- **WHEN** Business B is onboarded with no optional features enabled
- **THEN** Business B's users see navigation only for commission/settlements/Square-connected
  functionality, with no SMS/RAG/marketing menu items rendered, and the underlying code for those
  features is unchanged and available to enable later without a deploy

#### Scenario: Enabling a feature for one business does not affect another
- **WHEN** Business A has the RAG assistant enabled and Business B does not
- **THEN** Business B's users cannot access any RAG endpoint (404, not merely hidden in the UI), while
  Business A's access is unaffected
