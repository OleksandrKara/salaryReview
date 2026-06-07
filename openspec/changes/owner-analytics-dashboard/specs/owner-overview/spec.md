## ADDED Requirements

### Requirement: Owner overview endpoint returns monthly revenue and payroll data
The system SHALL expose `GET /api/owner/overview?year=YYYY` secured to OWNER role only. It SHALL return one entry per calendar month of the requested year, each containing: `month` (1–12), `label` (e.g. "Jan"), `cardRevenue`, `cashRevenue`, `grossRevenue` (= card + cash), `tips`, `procedures`, `avgPerAppt` (grossRevenue / procedures, null when procedures = 0), `payrollCost`, `payrollPct` (payrollCost / grossRevenue × 100, null when grossRevenue = 0), and `finalized` (boolean — true when sourced from settled PeriodEntry rows, false when sourced from live Square aggregation). It SHALL also return a `providers` array of year-to-date provider-level totals (providerId, name, ytdGross, ytdPayroll, ytdPayrollPct) and a `prevYear` summary object (totalGross, totalCard, totalCash) for year-over-year comparison.

#### Scenario: Year with settled months
- **WHEN** the owner requests `/api/owner/overview?year=2025` and PeriodEntry rows exist for several months of 2025
- **THEN** each settled month entry has `finalized: true` and its revenue values match the sum of all providers' cardTotal + cashTotal for that month across both halves

#### Scenario: Current month not yet settled
- **WHEN** the owner requests `/api/owner/overview?year=2026` and the current month has no PeriodEntry rows
- **THEN** the current month entry has `finalized: false` and revenue values sourced from SquareMonthAggregator

#### Scenario: Future months
- **WHEN** the response includes months after the current calendar month
- **THEN** those month entries have null revenue fields and `finalized: false`

#### Scenario: Non-owner access blocked
- **WHEN** a PROVIDER or MANAGER calls `GET /api/owner/overview?year=YYYY`
- **THEN** the response is 403 Forbidden

### Requirement: Payroll cost is computed from CommissionCalculator
The system SHALL compute monthly `payrollCost` by running `CommissionCalculator` on each provider's `HalfInput` (reconstructed from their `PeriodEntry` rows) and summing `zelleToProvider` plus the cash commission portion across all providers and both halves. For the live current-month entry, payroll SHALL be computed from the raw Square aggregation output without extra-lines adjustments, and the response SHALL set `finalized: false` to signal this is an estimate.

#### Scenario: Payroll percentage within normal range
- **WHEN** the service aggregates a month where providers earned 45/55 commission on all card revenue
- **THEN** `payrollPct` is approximately 45%

#### Scenario: Month with no activity
- **WHEN** a month has no PeriodEntry rows and is not the current month
- **THEN** `payrollCost` and `payrollPct` are null for that month

### Requirement: Owner overview page renders a year revenue chart
The system SHALL render a `/owner/overview` page accessible only to OWNER role users, showing a bar chart of 12 monthly bars for the selected year. Each bar height SHALL be proportional to that month's revenue relative to the year's maximum. The current live month bar SHALL be visually distinct (e.g. lighter fill). Clicking a bar SHALL select that month and update the KPI cards below the chart.

#### Scenario: Page redirects non-owner
- **WHEN** a PROVIDER or MANAGER navigates to `/owner/overview`
- **THEN** they are redirected to `/reports`

#### Scenario: Bar chart renders settled months
- **WHEN** the owner loads `/owner/overview?year=2026`
- **THEN** settled months show solid bars with dollar amounts below each bar

#### Scenario: Live month bar is visually distinct
- **WHEN** the current month has no settled PeriodEntry rows
- **THEN** its bar uses a lighter/dashed style and shows a "live" label

### Requirement: Channel toggle filters chart and KPI values
The system SHALL provide a three-way toggle (All / Card / Cash) on the overview page. Selecting Card SHALL display `cardRevenue` values in the chart and KPIs; selecting Cash SHALL display `cashRevenue`; selecting All SHALL display `grossRevenue`. The toggle SHALL operate client-side without a page reload, using state already pre-loaded in the initial response.

#### Scenario: Toggle to Card only
- **WHEN** the owner clicks "Card" in the channel toggle
- **THEN** the chart bars and gross KPI update to show card-only revenue without a network request

#### Scenario: Toggle to Cash only
- **WHEN** the owner clicks "Cash" in the channel toggle
- **THEN** tips are still shown (tips are always card-based) but the gross KPI and chart reflect cash revenue only

### Requirement: KPI cards show growth comparisons
The system SHALL display KPI cards for the selected month: gross revenue, percentage change vs the prior month, percentage change vs the same month in the prior year, payroll %, tips total, average revenue per appointment, and total service count. Percentage deltas SHALL show an up/down arrow and be color-coded (green for positive, red for negative).

#### Scenario: Month-over-month growth
- **WHEN** May gross is $14,200 and April gross was $13,000
- **THEN** the "vs prior month" KPI shows "↑ +9.2%" in green

#### Scenario: Missing prior-year data
- **WHEN** no PeriodEntry rows exist for the same month in the prior year
- **THEN** the "vs prior year" KPI shows "—" rather than an error

### Requirement: Provider breakdown table shows year-to-date revenue
The system SHALL display a table below the KPIs listing each provider's year-to-date gross revenue, payroll cost, and payroll percentage, sorted descending by gross revenue. The table SHALL reflect all settled months of the selected year combined.

#### Scenario: Table sorted by revenue
- **WHEN** the overview page loads for a year with data
- **THEN** providers appear in descending order of their gross revenue generated for the year

#### Scenario: Current month excluded from year-to-date table
- **WHEN** the current month is "live" (not finalized)
- **THEN** the provider table reflects only settled months to avoid mixing estimated and finalized figures

### Requirement: Year navigation and link from reports header
The system SHALL support `?year=YYYY` URL parameter on `/owner/overview` for year selection, with previous/next year navigation links. The `/reports` page header SHALL show an "Overview" link to `/owner/overview` visible only when the logged-in user has OWNER role.

#### Scenario: Year navigation
- **WHEN** the owner clicks the previous year arrow on the overview page
- **THEN** the URL changes to `?year=YYYY-1` and the page renders that year's data

#### Scenario: Reports header link visible to owner only
- **WHEN** an OWNER is on the `/reports` page
- **THEN** an "Overview" link is visible in the header area

#### Scenario: Reports header link hidden from non-owners
- **WHEN** a MANAGER or PROVIDER is on the `/reports` page
- **THEN** no "Overview" link is shown
