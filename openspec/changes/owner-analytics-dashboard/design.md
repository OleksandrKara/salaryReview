## Context

The existing `/reports` page computes and displays provider payroll for a single month, sourced live from Square via `SquareMonthAggregator`. There is no aggregated salon-level view across months. `PeriodEntry` rows in Postgres already store per-provider, per-half card gross, cash gross, tips, and procedure count for every finalized pay period — this data is the source of truth for historical revenue without touching Square.

The owner needs a year-view dashboard showing total salon revenue (card + cash), a payroll-to-revenue ratio, and month-on-month growth. The current month may not yet be finalized (no `PeriodEntry` rows), so it requires a live Square aggregation call.

## Goals / Non-Goals

**Goals:**
- Aggregate settled months from `PeriodEntry` DB rows with no Square API calls.
- Show current (unsettled) month from one live `SquareMonthAggregator` call, marked "live".
- Compute payroll cost per month by running `CommissionCalculator` over each provider's `HalfInput`.
- Expose data via a single `GET /api/owner/overview?year=YYYY` endpoint, OWNER-only.
- Render a year bar chart, KPI cards, and provider breakdown table in a new `/owner/overview` page.
- Channel toggle (All / Card / Cash) is client-side — all three channel values pre-loaded in one response.

**Non-Goals:**
- Multi-year chart on one screen.
- Rolling 12-month view (can be added later).
- Expense tracking beyond payroll.
- Customer-level or service-mix analytics.
- Any Flyway migration — no schema changes needed.

## Decisions

### D1: DB-only for settled months, one live call for current month

**Decision**: `OwnerOverviewService` first checks which months in the requested year have at least one `PeriodEntry` via `PayPeriodRepository`. For months with entries, it sums from the DB. For the current calendar month only (if no entries exist yet), it calls `SquareMonthAggregator` using the existing `priceCutoff` from `SalonConfig`. Months in the future or with no activity return nulls and are rendered as empty bars.

**Alternatives considered**:
- *Always call Square for every month*: 12 API calls per page load, too slow and burns Square rate limits.
- *Only show finalized months*: Simpler, but the current month is always missing — the owner's most important number.
- *Cache the live call*: Not worth the complexity at this scale.

### D2: Payroll cost via CommissionCalculator, not stored

**Decision**: `OwnerOverviewService` reconstructs a `HalfInput` from each `PeriodEntry` (cardTotal, cashTotal, tips, procedures, commissionRate) and runs `CommissionCalculator` per entry to get `zelleToProvider` and the cash commission portion. Monthly payroll = sum across all providers and both halves. No new DB columns.

**Rationale**: `PeriodEntry` already has all inputs. Storing the computed payroll would duplicate the commission engine's output and risk diverging if rates change. The calculation is fast (pure arithmetic, no I/O).

**Alternatives considered**:
- *Store payroll in a new column*: Adds migration complexity; computed value should not be persisted.

### D3: Channel toggle is client-side

**Decision**: The backend returns `cardRevenue`, `cashRevenue`, and `grossRevenue` (= card + cash) for every month in a single response. The `RevenueChart` client component switches between them with `useState`. No re-fetch on toggle.

**Rationale**: The three values are always needed; sending all three once avoids round-trips and makes the toggle instant.

### D4: Pure CSS/Tailwind bar chart, no new dependency

**Decision**: Bar heights are inline `style={{ height: '${pct}%' }}` relative to the max monthly value, using Tailwind utility classes. No charting library.

**Rationale**: The chart is simple (12 vertical bars, click to select). A library would add bundle size and a learning curve for future contributors. The existing codebase has no charting dependency.

### D5: Year navigation via URL search param

**Decision**: `?year=YYYY` in the URL, consistent with `/reports?year=&month=`. The `MonthNav`-style year nav component updates the URL. Server component re-renders on year change.

**Rationale**: Bookmarkable, shareable, consistent with the rest of the app.

### D6: Security — OWNER role only

**Decision**: `SecurityConfig` adds `/api/owner/**` → `hasRole('OWNER')`. The frontend `/owner/overview` page checks `me.role === 'OWNER'` server-side and redirects to `/reports` if not.

**No new Flyway migration required.**

## Risks / Trade-offs

- **Live call latency**: The current-month Square aggregation takes 2–5 s. This blocks the server component render. Mitigation: wrap it in a React `<Suspense>` boundary so the settled-months chart renders immediately and the current month bar streams in.
- **Payroll % accuracy for current month**: The live aggregator does not run the full `applyExtraLines` path (redos, manual credits, prepaid) because those are stored in DB-backed services, not re-derived from Square. Payroll for the "live" month bar is therefore an estimate (commission on raw Square data only). Mitigation: mark the current month clearly as "live / estimate".
- **Months with partial data**: A month where only one half has been settled shows totals for that half only. This is correct and expected — the bar just looks smaller.
- **priceCutoff for historical payroll**: `SalonConfig` has one global `priceCutoff`. If the cutoff changed in the past, historical payroll % is slightly off. Acceptable at this stage.

## Open Questions

- Should the provider breakdown table link to that provider's existing `/reports/{id}` detail page? (Low friction to add; left for implementation.)
- Should MANAGER role also see this page? Currently scoped to OWNER only per the proposal.
