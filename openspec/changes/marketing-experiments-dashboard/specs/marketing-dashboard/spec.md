## ADDED Requirements

### Requirement: Marketing dashboard endpoint returns variant performance
The system SHALL expose `GET /api/owner/marketing?slug=` (default slug `"mani"`) secured to
OWNER role only. It SHALL return `available` (boolean), `landingPageSlug`, `experimentStatus`
(`"active" | "paused" | "none"`), and a `variants` array where each entry has `variantId`, `name`,
`weight`, `active`, `pageViews`, `bookingsCompleted`, and `conversionRate` (bookingsCompleted /
pageViews, `0` when pageViews is `0`). Data SHALL be read directly from the `marketing` Postgres
schema (tables owned by the separate `salonLandings` service) via read-only SQL, never written.

#### Scenario: Active experiment with variant data
- **WHEN** the owner requests `/api/owner/marketing?slug=mani` and an active experiment with
  variants and recorded events/attribution exists for that slug
- **THEN** the response has `available: true`, `experimentStatus: "active"`, and one `variants`
  entry per active variant with accurate `pageViews`/`bookingsCompleted`/`conversionRate`

#### Scenario: No experiment configured yet
- **WHEN** the requested slug has a `landing_pages` row but no `experiments` row
- **THEN** the response has `experimentStatus: "none"` and the single fallback variant's stats

#### Scenario: Marketing schema unavailable
- **WHEN** the `marketing` schema or its tables do not exist yet (e.g. `salonLandings` has not
  run its migrations)
- **THEN** the response has `available: false` and an empty `variants` array — the endpoint
  returns 200, not a 500, and Operations Portal's own health is unaffected

#### Scenario: Non-owner access blocked
- **WHEN** a PROVIDER or MANAGER calls `GET /api/owner/marketing`
- **THEN** the response is 403 Forbidden

### Requirement: Marketing dashboard page renders variant performance
The system SHALL render a `/owner/marketing` page accessible only to OWNER role users, showing an
experiment status badge and a table of variants with weight, active flag, page views, bookings,
and conversion rate.

#### Scenario: Page redirects non-owner
- **WHEN** a PROVIDER or MANAGER navigates to `/owner/marketing`
- **THEN** they are redirected to `/reports`

#### Scenario: Empty state when data is unavailable
- **WHEN** the backend responds with `available: false`
- **THEN** the page renders an empty-state panel instead of an empty or broken table

#### Scenario: Nav link visible to owner only
- **WHEN** an OWNER is viewing any page with the `AdminMenu`
- **THEN** a "Marketing" link to `/owner/marketing` is visible in the menu

#### Scenario: Nav link hidden from non-owners
- **WHEN** a MANAGER or PROVIDER views the `AdminMenu`
- **THEN** no "Marketing" link is shown
