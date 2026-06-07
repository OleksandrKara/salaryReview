## Why

The `/reports` page shows what to pay each provider but gives the owner no salon-level view of revenue, growth, or profitability. The owner has no quick answer to "are we growing?" or "is our payroll ratio healthy?" without manually summing numbers across pay periods.

## What Changes

- New owner-only page `/owner/overview` showing salon gross revenue month-by-month for a selected year.
- Bar chart (pure CSS/Tailwind, no new library) with cash/card/all toggle and year navigation.
- KPI cards: gross revenue, vs prior month %, vs prior year %, payroll-to-revenue %, tips, avg revenue per appointment, service count.
- Provider revenue breakdown table (year-to-date, sorted by revenue generated).
- New backend endpoint `GET /api/owner/overview?year=YYYY` — aggregates from `PeriodEntry` DB rows for settled months (fast, no Square calls) plus one live `SquareMonthAggregator` call for the current unfinished month (marked "live").
- Link to the new page from the `/reports` header (OWNER role only).

## Non-goals

- No real-time updates or websocket streaming.
- No expense tracking (rent, supplies) — only revenue and payroll are computed here.
- No customer-level analytics (repeat visit rate, retention).
- No service-mix breakdown (which service types drive revenue).
- No changes to the commission engine or payout logic.
- No Square write operations.

## Capabilities

### New Capabilities

- `owner-overview`: Month-by-month salon revenue dashboard (gross by channel, payroll %, KPIs, provider table) accessible to OWNER role only.

### Modified Capabilities

*(none — no existing spec-level requirements change)*

## Impact

- **Backend**: New `OwnerOverviewController` + `OwnerOverviewService`. Reads `PeriodEntryRepository` and `PayPeriodRepository`; runs `CommissionCalculator` for payroll cost. Calls `SquareMonthAggregator` for current month. OWNER-secured endpoint.
- **Frontend**: New `app/owner/overview/page.tsx` (server component), plus a `RevenueChart` client component for the toggle/chart interaction. New proxy route `app/api/owner/overview/route.ts`.
- **Navigation**: `AdminMenu` (or reports header) gains an "Overview" link shown only to OWNER.
- **Dependencies**: No new libraries.
- **Verification**: Backend unit test for `OwnerOverviewService` aggregation logic. Manual check at `localhost:3000/owner/overview` logged in as `olexandr.kara2`.
