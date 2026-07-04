## Why

The owner now runs A/B/n tests on landing-page variants for the separate `salonLandings`
marketing site (mani.akluxnails.com). That service writes experiment, event, and attribution
data into a `marketing` Postgres schema on the same database instance as this app, but there is
no visibility into any of it from inside the Operations Portal — checking variant performance
today requires querying Postgres by hand.

## What Changes

- New owner-only page `/owner/marketing` showing, for a landing page (default slug `"mani"`):
  experiment status (active/paused/none) and a per-variant table of weight, active flag, page
  views, completed bookings, and conversion rate (bookings / page views).
- New backend endpoint `GET /api/owner/marketing?slug=` — reads the `marketing` schema
  cross-schema, **read-only**, via plain `JdbcTemplate` (not a JPA entity — see design.md for why).
- Link to the new page from the `AdminMenu` (OWNER role only).

## Non-goals

- No write path back into the `marketing` schema — this app never mutates another service's data.
- No experiment create/pause/edit UI — read-only visibility only.
- No changes to the `salonLandings` repo itself (already implemented there, out of scope here).
- No alerting or notifications.
- No Flyway migration — this app owns no tables in the `marketing` schema.

## Capabilities

### New Capabilities

- `marketing-dashboard`: Read-only, OWNER-only view of landing-page experiment performance
  (page views, bookings, conversion rate per variant, experiment status) sourced from the
  `marketing` Postgres schema owned by the separate `salonLandings` service. Resilient to that
  schema being absent, mid-migration, or temporarily unreachable.

### Modified Capabilities

*(none — no existing spec-level requirements change)*

## Impact

- **Backend**: New `MarketingDashboardController` + `MarketingDashboardService` +
  `MarketingDashboardRepository` (plain `JdbcTemplate` against the existing `DataSource`, no new
  connection pool). New DTOs. No `SecurityConfig` change — `/api/owner/marketing` is already
  covered by the existing `/api/owner/**` → `hasRole('OWNER')` catch-all.
- **Frontend**: New `app/owner/marketing/page.tsx` (server component), `VariantTable.tsx`,
  `ExperimentStatusBadge.tsx`, an empty-state panel. New proxy `app/api/owner/marketing/route.ts`.
  `AdminMenu` gains an "Marketing" link (OWNER only) + new `navMarketing` i18n key.
- **Dependencies**: None new — `JdbcTemplate` ships transitively via
  `spring-boot-starter-data-jpa`.
- **Verification**: Backend unit test for `MarketingDashboardService` (conversion-rate math +
  the "returns an unavailable DTO instead of throwing when the marketing schema is unreachable"
  case). Manual check at `localhost:3000/owner/marketing` logged in as `olexandr.kara2`, with data
  seeded via the `salonLandings` service (or by hand in the `marketing` schema).
