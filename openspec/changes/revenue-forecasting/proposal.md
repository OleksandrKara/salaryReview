## Why

The `/reports` revenue-pulse panel today shows a naive month-end projection: `MTD + upcoming-confirmed-bookings`. This systematically overshoots because some bookings cancel or no-show, and it ignores what historical patterns tell us about how the month typically completes. The owner needs a credible, data-driven projection — ideally a range with a most-likely midpoint — to make staffing and operational decisions in the middle of the month.

## What Changes

- Replace the naive "MTD + upcoming" projection on the revenue-pulse panel with a forecaster that blends two techniques:
  - **Pattern matching**: uses existing `PeriodEntry` half-month totals to compute "historically, what % of monthly revenue was in the bank by this day-of-month". Works from day one — no new data needed.
  - **Booking-ceiling calibration**: learns the bias of the naive projection against actual month-end. Warms up after 3+ months of snapshot data; once active, it's blended into the final output.
- The forecaster returns three numbers: `projectedMid` (most-likely), `projectedLow` (range floor), `projectedHigh` (range ceiling). Cold-start (insufficient history) falls back to the naive projection with a wider implicit range.
- Daily snapshot job runs at 1:30 AM salon-local via Spring `@Scheduled`, capturing `MTD revenue / card / cash / services / upcoming-booking count / upcoming-gross`. Idempotent by `UNIQUE(snapshot_date)`; on app startup, missing recent days are backfilled.
- Month-end actual is filled into each snapshot row after the month closes (a second scheduled job runs on the 1st of each month for the prior month) so each snapshot becomes a complete `(prediction, outcome)` pair for future calibration.
- Revenue-pulse panel UI shows the range with a most-likely midpoint and a small "vs prior month" delta. Calibration confidence level is implied by range width (narrower = more confident).

## Non-goals

- No machine-learning model, no external libraries. Pure arithmetic — averages, ratios, weighted blends.
- No day-of-week pattern modeling, no holiday detection, no growth-trend correction (those become later phases if Phase 1 proves useful).
- No multi-tenant / per-provider forecasting — salon-wide month-end only.
- No Square writes (Square remains read-only).
- No new dependencies (no Quartz, no ML libs — Spring's built-in `@Scheduled` only).
- No backfill of `revenue_snapshot` rows for past dates from current Square state — that would re-introduce the "current-state vs historical-state" drift problem; we start fresh.

## Capabilities

### New Capabilities

- `revenue-forecasting`: A month-end revenue forecaster that captures daily snapshots, exposes a calibrated projection (low/mid/high range), and powers the revenue-pulse panel's projected-total display.

### Modified Capabilities

*(none — no existing spec-level requirements change; the revenue-pulse panel currently has no spec file)*

## Impact

- **Backend**: New `RevenueSnapshot` JPA entity + `RevenueSnapshotRepository`. New `RevenueSnapshotService` (capture + month-end-actual fill). New `RevenueForecastService` (pattern-match + calibration math). `RevenuePulseService` integrates the forecast into its DTO. `RevenuePulseDto` gains `projectedLow`, `projectedMid`, `projectedHigh` fields (the existing `projectedMonthGross` becomes the "naive" cross-check value kept for transparency, or is replaced — design will decide).
- **DB**: New Flyway migration `V18__revenue_snapshot.sql` creating `revenue_snapshot` table with `UNIQUE(snapshot_date)`.
- **Scheduling**: New `@EnableScheduling` on the application config. Two `@Scheduled` methods: daily snapshot at 01:30 salon-local, monthly actual-fill at 02:00 salon-local on day-1.
- **Frontend**: `RevenuePulse.tsx` renders the range and midpoint instead of a single projected total. `RevenuePulse` TS type adds `projectedLow`, `projectedMid`, `projectedHigh`.
- **Dependencies**: No new libraries.
- **Verification**: Backend unit tests for `RevenueForecastService` (pattern-match math, calibration ratio, blend, cold-start fallback). Manual check at `localhost:3000/reports` logged in as `olexandr.kara2` after a few days of snapshot rows accumulate.
