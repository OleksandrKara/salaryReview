## 1. Backend — Migration and domain

- [x] 1.1 Create Flyway migration `V19__revenue_snapshot.sql` (V18 already taken by `no_show_fee_override`) with table `revenue_snapshot` (id BIGSERIAL PK, snapshot_date DATE UNIQUE NOT NULL, mtd_revenue/card/cash NUMERIC(10,2), mtd_services INT, upcoming_count INT, upcoming_gross NUMERIC(10,2), month_end_actual NUMERIC(10,2) nullable, created_at TIMESTAMPTZ default now)
- [x] 1.2 Create `RevenueSnapshot` JPA entity in `com.salonreview.domain` mirroring the columns
- [x] 1.3 Create `RevenueSnapshotRepository extends JpaRepository<RevenueSnapshot, Long>` with finders: `findBySnapshotDate(LocalDate)`, `findTopByOrderBySnapshotDateDesc()`, `findAllByMonthEndActualIsNotNullOrderBySnapshotDateDesc(Pageable limit)`, `findAllBySnapshotDateBetween(LocalDate from, LocalDate to)`

## 2. Backend — Snapshot capture service

- [x] 2.1 Create `RevenueSnapshotService` with `captureFor(LocalDate date)` that: calls `SquareMonthAggregator.aggregate()` for the date's year/month, sums MTD totals up through `date`, computes upcoming-booking count and gross for `date+1 → end-of-month`, writes the row only if no existing row for that `snapshot_date`
- [x] 2.2 Add `backfillRecent()` to `RevenueSnapshotService` — captures up to 3 most-recent missing dates ending yesterday; called from `@PostConstruct` hook (in a separate `@Component` to keep service free of lifecycle annotations)
- [x] 2.3 Add `fillMonthEndActualsFor(YearMonth)` to `RevenueSnapshotService` that sums `PeriodEntry` revenue for that month and updates every snapshot row in that month with `month_end_actual`; logs warning and returns 0 if no entries exist

## 3. Backend — Scheduling

- [x] 3.1 Add `@EnableScheduling` to `SalonreviewApplication`
- [x] 3.2 Create `RevenueSnapshotScheduler` (used `SchedulingConfigurer` instead of `@Scheduled` so the salon timezone resolved at runtime can drive the cron trigger): daily at 01:30 captures yesterday, monthly at 02:00 day 1 fills prior month actuals
- [x] 3.3 Salon timezone resolved at startup inline in the scheduler via `SquareClient.locationTimeZone()` with UTC fallback (no separate bean — only one consumer)
- [x] 3.4 Create `RevenueSnapshotStartup` `@Component` that listens to `ApplicationReadyEvent` and calls `RevenueSnapshotService.backfillRecent()` (using ApplicationReadyEvent instead of `@PostConstruct` so the full context — including SquareClient — is up before backfill runs)

## 4. Backend — Forecaster

- [x] 4.1 Create `RevenueForecastService` with public method `ForecastResult forecast(int year, int month, BigDecimal currentMTD, BigDecimal upcomingGross)`
- [x] 4.2 Implement `patternMatch()` — pulls last 6 settled months from `PeriodEntryRepository` + `PayPeriodRepository`, computes per-month `firstHalfRatio = firstHalfTotal / monthTotal`, averages, returns `currentMTD / avgRatio`. Returns null when `< 3` settled months.
- [x] 4.3 Implement `calibration()` — pulls last 6 `revenue_snapshot` rows with non-null `month_end_actual`, computes mean of `month_end_actual / (mtd_revenue + upcoming_gross)`, returns `(currentMTD + upcomingGross) * meanBias`. Returns null when `< 3` calibration rows (deduped to one row per month so daily snapshots don't dominate).
- [x] 4.4 Implement `blend()` — applies the weight table from spec D4 to compute `projectedMid`, then `projectedLow = min(pattern, calibration) * 0.9`, `projectedHigh = max(pattern, calibration) * 1.1`. Single-technique fallback: `mid * 0.85` to `mid * 1.15`. Cold start: returns `mid = currentMTD + upcomingGross`, `low = high = null`.
- [x] 4.5 Create `ForecastResult` record with: `projectedMid`, `projectedLow` (nullable), `projectedHigh` (nullable), `calibrationDataPoints` (int), `historyMonths` (int)

## 5. Backend — Wire forecast into RevenuePulse

- [x] 5.1 Add `RevenueForecastService` as a constructor dep of `RevenuePulseService`
- [x] 5.2 Inside `RevenuePulseService.pulse()`, after computing `currentGross` and `upcoming`, call `forecastService.forecast(...)` and put its fields into the DTO
- [x] 5.3 Update `RevenuePulseDto` to add `projectedMid`, `projectedLow`, `projectedHigh`, `forecastCalibrationDataPoints`, `forecastHistoryMonths`; kept `projectedMonthGross` as the transparent naive cross-check

## 6. Backend — Tests

- [x] 6.1 Write `RevenueForecastServiceTest`: cold-start (0 history → naive fallback, no range); pattern-only (6 settled months, no calibration → ±15% range); below-3-rows calibration falls back to pattern; full blend (6 settled + 6 calibration → weighted blend)
- [x] 6.2 Write `RevenueSnapshotServiceTest`: idempotent re-run; backfillRecent captures last 3 days; `fillMonthEndActualsFor` writes to every snapshot in month; warning + 0 when no PeriodEntry
- [x] 6.3 Write `RevenueSnapshotSchedulerTest` — uses Spring's `CronExpression.parse()` to verify the daily-at-01:30 and monthly-day-1-at-02:00 firing schedules

## 7. Frontend — Types and proxy

- [x] 7.1 Update `RevenuePulse` interface in `frontend/app/lib/types.ts` to add `projectedLow: number | null`, `projectedMid: number`, `projectedHigh: number | null`, `forecastCalibrationDataPoints: number`, `forecastHistoryMonths: number`
- [x] 7.2 No proxy route changes needed — the existing `/api/owner/pulse` route forwards the larger DTO transparently

## 8. Frontend — RevenuePulse UI

- [x] 8.1 Update `frontend/app/reports/RevenuePulse.tsx` right panel: show `~projectedMid` as the primary value; show range `$LOW – $HIGH` below; cold-start (no range) shows "Naive estimate · more history needed"
- [x] 8.2 Calibration-status dot + label near the projection: gray = "calibrating" (<3 months history), amber = "warming up" (history but no calibration yet), green = "calibrated" (3+ calibration rows). Tooltip on hover with details.
- [x] 8.3 Matched existing zinc design system; range and badges use zinc/amber/green-500 minimally for status only

## 9. Verification

- [x] 9.1 `./mvnw test` — all new tests (10 total in RevenueForecastServiceTest + RevenueSnapshotServiceTest + RevenueSnapshotSchedulerTest) pass. `contextLoads` fails locally without DB (per AGENTS.md, passes in CI)
- [x] 9.2 `npx tsc --noEmit` — no TS errors
- [x] 9.3 Rebuilt Docker; backend startup logs confirm: "Revenue snapshot scheduler bound to zone America/Los_Angeles" and 3 snapshots auto-captured by startup backfill (Jun 11/12/13 with real MTD revenue and upcoming counts)
- [x] 9.4 Startup backfill auto-tested the capture flow — no manual trigger needed
