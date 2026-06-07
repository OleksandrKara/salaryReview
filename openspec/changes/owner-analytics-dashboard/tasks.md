## 1. Backend — DTO and service

- [x] 1.1 Create `OwnerOverviewDto` record with: `year`, `months` (list of `MonthSummary`), `providers` (list of `ProviderYtd`), `prevYear` (`YearTotals`)
- [x] 1.2 Create `MonthSummary` record with: `month`, `label`, `cardRevenue`, `cashRevenue`, `grossRevenue`, `tips`, `procedures`, `avgPerAppt`, `payrollCost`, `payrollPct`, `finalized`
- [x] 1.3 Create `ProviderYtd` record with: `providerId`, `name`, `ytdGross`, `ytdPayroll`, `ytdPayrollPct`
- [x] 1.4 Create `YearTotals` record with: `totalGross`, `totalCard`, `totalCash`
- [x] 1.5 Add `findAllByYearOrderByMonthAscHalfAsc(int year)` to `PayPeriodRepository`
- [x] 1.6 Create `OwnerOverviewService`: for each month of the requested year, sum PeriodEntry rows (cardTotal + cashTotal + tips + procedures) across all providers and both halves via `PeriodEntryRepository`
- [x] 1.7 In `OwnerOverviewService`: run `CommissionCalculator` per entry (reconstructing `HalfInput` from PeriodEntry fields + SalonConfig priceCutoff) to compute `payrollCost` per month
- [x] 1.8 In `OwnerOverviewService`: for the current calendar month if no PeriodEntry rows exist, call `SquareMonthAggregator.aggregate()` and derive revenue + payroll from its result, setting `finalized: false`
- [x] 1.9 In `OwnerOverviewService`: build `providers` list from settled months only (year-to-date), summing per-provider gross and payroll, sorted descending by gross
- [x] 1.10 In `OwnerOverviewService`: fetch prior year data (same DB query, year-1) and compute `prevYear` totals

## 2. Backend — Controller and security

- [x] 2.1 Create `OwnerOverviewController` with `GET /api/owner/overview?year=YYYY` mapping that delegates to `OwnerOverviewService` and returns `OwnerOverviewDto`; default year to current calendar year when param is absent
- [x] 2.2 In `SecurityConfig`, add `/api/owner/**` → `hasRole('OWNER')` rule (above the existing catch-all)

## 3. Backend — Tests

- [x] 3.1 Write `OwnerOverviewServiceTest` covering: settled months aggregate correctly from PeriodEntry, payroll % is ~45% for a base-rate provider, month with no entries returns nulls, future months return nulls

## 4. Frontend — API proxy and types

- [x] 4.1 Add `OwnerOverviewDto`, `MonthSummary`, `ProviderYtd`, `YearTotals` TypeScript types to `frontend/app/lib/types.ts`
- [x] 4.2 Create `frontend/app/api/owner/overview/route.ts` proxy using `proxyGet` to forward `?year=` to `GET /api/owner/overview`
- [x] 4.3 Add `getOwnerOverview(year: number)` to `frontend/app/lib/serverApi.ts`

## 5. Frontend — RevenueChart client component

- [x] 5.1 Create `frontend/app/owner/overview/RevenueChart.tsx` as a `'use client'` component that accepts the full months array and renders 12 CSS/Tailwind bars with height proportional to the selected channel's max value
- [x] 5.2 Implement channel toggle state (All / Card / Cash) in `RevenueChart`; switching updates bar heights and passes selected month index up via `onMonthSelect` callback — no network request
- [x] 5.3 Render the current live month bar with a lighter fill and a small "live" label; empty/future months render as a minimal placeholder bar
- [x] 5.4 Show the dollar amount and month label below each bar; highlight the selected bar

## 6. Frontend — KPI cards and provider table

- [x] 6.1 Create `frontend/app/owner/overview/KpiCards.tsx` (client component) that receives the selected `MonthSummary` + prior month + prior year month and renders: gross, vs-prior-month %, vs-prior-year %, payroll %, tips, avg/appt, services; delta values color-coded green/red with arrow
- [x] 6.2 Create `frontend/app/owner/overview/ProviderTable.tsx` (server-renderable) that displays the `ProviderYtd` rows sorted by gross descending with columns: Name, YTD Gross, Payroll Cost, Payroll %

## 7. Frontend — Overview page

- [x] 7.1 Create `frontend/app/owner/overview/page.tsx` as a server component; read `?year=` from `searchParams`, call `getOwnerOverview(year)`, redirect to `/reports` if `me.role !== 'OWNER'`
- [x] 7.2 Add year navigation (prev / current year label / next) to the page using URL search param updates, consistent with `MonthNav` pattern
- [x] 7.3 Wrap the live-month fetch in a `<Suspense>` boundary so settled bars appear immediately and the current month streams in (pass finalized months synchronously, live month data separately if needed)
- [x] 7.4 Compose `RevenueChart`, `KpiCards`, and `ProviderTable` on the page; wire `onMonthSelect` so clicking a bar updates KPI cards client-side

## 8. Frontend — Navigation link

- [x] 8.1 In `frontend/app/reports/AdminMenu.tsx` (or the reports page header), add an "Overview" link to `/owner/overview` visible only when `isOwner` is true
