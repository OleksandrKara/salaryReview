## Why

The owner wants to judge **provider effectiveness** beyond revenue: who keeps clients, who grows their book, and — critically — whether the salon's **new-client pipeline is leaking** through a provider (we send fresh clients to P and they never come back). Today the app computes everything per-month, on demand, from Square; it persists no customer-visit history, so it cannot answer longitudinal questions like "is this client new or returning to provider Y?", "did P's new clients come back?", or "how is P's clientele trending?".

Square's Appointments → Performance report shows *some* of this (per-staff new vs returning, rebooking rate) but only as a dashboard, with new defined at the **business** level (not per provider), and no cohort retention, trend, or composite score — and the aggregations aren't a clean API to pull.

This change adds a **persistent visit ledger** (fed by the aggregator the app already runs, like `revenue_snapshot`) and a **per-provider retention/effectiveness view** computed from it.

## What Changes

- A new **visit-fact ledger** (`provider_visit`): one row per `(customer, provider, service_date)`, plus a `rebooked_same_day` flag and a service category (Nails/PMU). Populated **daily** from the month aggregator's `AttributedService` tuples (which already carry `customerId`/`providerId`/`date`) and **backfilled** once over recent history.
- `SquareClient.Booking` maps Square's **`created_at`** (currently unmapped) so "same-day rebook" can be detected.
- A **per-provider, per-month analytics view** (owner-only) showing, computed from the ledger by query:
  - **Volume**: clients seen, new-to-provider, returning-to-provider, and new-to-salon-via-P ("fresh clients P acquired").
  - **Retention (cohort)**: of P's new clients in month M, the % who returned within a window K — both **to P** (provider retention) and **to the salon** (salon retention) — with immature cohorts clearly marked.
  - **Same-day rebook rate** per provider.
  - **Trend**: month-over-month clientele size and new/returning mix.
  - **Acquisition-leak risk**: a flag when P receives many new-to-salon clients but their retention is below a threshold.
  - **Scorecard**: 3–4 honest metrics; an *optional, transparent* composite score is a later phase, not a single opaque number.

## Capabilities

### New Capabilities
- `provider-retention-analytics`: a persisted customer-visit ledger and the per-provider monthly retention / new-vs-returning / rebook / trend metrics derived from it.

### Modified Capabilities
*(none — additive; no change to settlements, the aggregator's outputs, or existing endpoints.)*

## Impact

- **Backend**: new `provider_visit` table + entity/repo; a visit-ingest service (daily accrual + one-time backfill) reusing `SquareMonthAggregator`; a retention/analytics service computing the metrics by SQL (window functions for first-visit/cohorts); an owner-only controller; one new mapped field on `Booking` (`created_at`); likely a scheduler hook alongside the existing revenue-snapshot jobs.
- **Frontend**: an owner-only analytics page (e.g. under `/owner/...`) with a provider table + per-provider trend/cohort visuals; mobile + web. PII-heavy (customer counts; optional customer-level drill-down) → **owner-only** access.
- **DB**: one new table (+ indexes on `(provider_id, service_date)` and `(customer_id, service_date)`); a one-time backfill job.
- **Square load**: ingest reuses cached aggregator calls; backfill is bounded (target 12–24 months).
- **Out of scope / Non-goals**: changing payroll/commission; a single opaque "effectiveness score" (scorecard first; composite is a transparent later phase); per-service-category retention windows (start with one window); inferring *assignment intent* ("who decided to give P the client" — Square doesn't expose it; we measure first-time pairings); manager access.

## Verification

- Ledger ingest is idempotent on `(customer, provider, date)`; backfill reproduces the same rows as daily accrual for a past month.
- New/returning, retention cohorts, and rebook rate match hand-computed values on a seeded fixture.
- Immature cohorts are flagged (not shown as "0%/bad").
- The view is owner-only (managers/providers blocked) and works on mobile + web.
