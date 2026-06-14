## ADDED Requirements

### Requirement: Daily revenue snapshots are captured automatically
The system SHALL capture one `revenue_snapshot` row per calendar day, fired by a scheduled job at 01:30 in the salon's local timezone. Each row SHALL include the prior day's date and that day's month-to-date totals: revenue (gross), card revenue, cash revenue, services count, upcoming-booking count (for the rest of the month), and upcoming-booking gross. The job SHALL be idempotent — re-running the same date SHALL NOT create a duplicate row.

#### Scenario: Job runs at scheduled time
- **WHEN** the salon-local time crosses 01:30 on any day
- **THEN** a new `revenue_snapshot` row exists for the prior calendar day with all MTD and upcoming fields populated

#### Scenario: Idempotent re-run
- **WHEN** the scheduled job runs and a snapshot already exists for that date
- **THEN** no new row is created and no error is raised

#### Scenario: App startup after multi-day outage
- **WHEN** the application starts and the latest snapshot's date is older than yesterday
- **THEN** the system backfills missing snapshots for up to the prior 3 days

### Requirement: Month-end actuals are filled in after a month closes
The system SHALL update `month_end_actual` on each snapshot row once that snapshot's month has fully closed. A monthly job SHALL run on day 1 of each month at 02:00 salon-local; it SHALL compute the prior month's total revenue from `PeriodEntry` rows and write that value to every snapshot row whose `snapshot_date` was in that prior month.

#### Scenario: Month closes and actuals fill in
- **WHEN** the first day of a new month begins and the prior month has settled `PeriodEntry` rows
- **THEN** every snapshot row from the prior month has its `month_end_actual` set to the sum of that month's `PeriodEntry` revenue

#### Scenario: Prior month has no PeriodEntry data
- **WHEN** the monthly job runs but no `PeriodEntry` rows exist for the prior month
- **THEN** the snapshot rows' `month_end_actual` remains null and the job logs a warning rather than failing

### Requirement: Pattern-matching forecast uses existing historical data
The system SHALL compute a pattern-matching projection from existing `PeriodEntry` data with no dependency on `revenue_snapshot`. The formula SHALL use the average ratio `first_half_total / (first_half + second_half)` over the most recent 6 settled months for which both halves are present. Current MTD divided by that average ratio SHALL yield the pattern-matching projection. When fewer than 3 settled months are available, the forecaster SHALL fall back to the naive projection (`MTD + upcoming-confirmed-gross`) without a range.

#### Scenario: 6+ settled months available
- **WHEN** the forecaster runs with 6 or more settled months in `PeriodEntry`
- **THEN** the pattern-matching projection is `currentMTD / avg(first_half_ratio over last 6 months)`

#### Scenario: 1-2 settled months only
- **WHEN** the forecaster runs with only 1 or 2 settled months
- **THEN** the forecast result has `projectedMid = currentMTD + upcomingGross` and `projectedLow = projectedHigh = null` (no range)

### Requirement: Booking-ceiling calibration learns the bias of the naive projection
The system SHALL compute a calibration factor when 3 or more `revenue_snapshot` rows have a non-null `month_end_actual`. The factor SHALL be the mean of `month_end_actual / (mtd_revenue + upcoming_gross)` over the most recent 6 such rows. Today's calibrated projection SHALL be `(currentMTD + currentUpcomingGross) * biasFactor`.

#### Scenario: 0 calibration data points available
- **WHEN** no `revenue_snapshot` row has a `month_end_actual`
- **THEN** the calibration component returns no value and the final projection uses pattern matching only

#### Scenario: 3-5 calibration data points available
- **WHEN** the forecaster has 3 to 5 snapshots with `month_end_actual` filled in
- **THEN** the calibration factor uses all available rows (not capped at 6)

#### Scenario: 6+ calibration data points available
- **WHEN** the forecaster has 6 or more snapshots with `month_end_actual` filled in
- **THEN** the calibration factor uses only the 6 most recent rows by `snapshot_date`

### Requirement: Final forecast blends pattern and calibration with weighted average
The system SHALL combine the pattern-matching and calibration projections into a single `projectedMid` value using these weights based on calibration data count. Counts below 3 do NOT engage calibration (per the previous requirement); the table covers only the active path.

| Calibration months (3+ required) | Pattern weight | Calibration weight |
|----------------------------------|---------------|--------------------|
| 3-5                              | 0.5           | 0.5                |
| 6+                               | 0.3           | 0.7                |

The system SHALL compute a range as `projectedLow = min(pattern, calibration) * 0.9` and `projectedHigh = max(pattern, calibration) * 1.1`. When only pattern is available (no calibration, or fewer than 3 calibration months), the range SHALL be `projectedMid * 0.85` to `projectedMid * 1.15`.

#### Scenario: Pattern $16,438 and calibration $14,822 with 6 rows
- **WHEN** pattern projects $16,438 and calibration projects $14,822 with 6 calibration rows
- **THEN** `projectedMid = 16438 * 0.3 + 14822 * 0.7 = 15307`, `projectedLow = 14822 * 0.9 = 13340`, `projectedHigh = 16438 * 1.1 = 18082`

#### Scenario: Pattern only (no calibration or under 3 rows)
- **WHEN** pattern projects $16,438 and fewer than 3 calibration months exist
- **THEN** `projectedMid = 16438`, `projectedLow = 13972`, `projectedHigh = 18904`

### Requirement: Revenue-pulse panel displays the forecast range
The system SHALL update the revenue-pulse panel on `/reports` to display `projectedMid` as the primary projection figure, with `projectedLow` and `projectedHigh` shown as the supporting range. When the forecaster returns no range (cold-start fallback), the panel SHALL show only `projectedMid` along with a "calibrating" indicator. The panel SHALL be owner-only as today.

#### Scenario: Owner views pulse with full forecast
- **WHEN** an owner loads `/reports` and the forecaster returns mid + low + high
- **THEN** the pulse panel shows `~$X,XXX` as the midpoint and `$Y,YYY – $Z,ZZZ` as the range

#### Scenario: Owner views pulse in cold-start mode
- **WHEN** an owner loads `/reports` and the salon has fewer than 3 settled months
- **THEN** the pulse panel shows the single naive projection with a "calibrating" indicator and no range

### Requirement: Forecasting endpoints are owner-only
The system SHALL only expose forecast data through the existing `/api/owner/pulse` endpoint, which is already gated to `OWNER` role. No new endpoints SHALL be introduced for forecasting. The scheduled jobs SHALL run as system tasks without authentication context.

#### Scenario: Non-owner requests pulse
- **WHEN** a PROVIDER or MANAGER calls `GET /api/owner/pulse`
- **THEN** the response is 403 Forbidden and no forecast data is returned

#### Scenario: Scheduled job runs
- **WHEN** the daily snapshot job runs
- **THEN** it operates without an authenticated principal and successfully writes a `revenue_snapshot` row
