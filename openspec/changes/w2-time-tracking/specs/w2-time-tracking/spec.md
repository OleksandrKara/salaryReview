## ADDED Requirements

### Requirement: Employment profile with effective-dated rate

The system SHALL store, per provider, an employment type (`COMMISSION` or `W2`) and an effective-dated hourly rate and mobile number. Only OWNER may change them. Time tracking features SHALL apply only to providers whose employment type is `W2` on the date in question.

#### Scenario: Rate change applies from its effective date
- **WHEN** the OWNER sets a provider's rate to $18.50 effective 2026-10-01
- **THEN** every work date on or after 2026-10-01 uses $18.50 and earlier dates keep their prior rate

#### Scenario: Commission providers unaffected
- **WHEN** a provider's type is COMMISSION
- **THEN** no proposal, reminder, timecard or finding is produced for that provider

### Requirement: Draft derived from appointments

The system SHALL derive a draft day for a W2 provider from that provider's own appointment segments: each segment contributes [start - 15 min, end + 15 min], overlapping and touching intervals merge, and a NO_SHOW booking contributes 30 minutes ([start - 15, start + 15]). Bookings cancelled by the seller SHALL contribute no time. Segments belonging to another team member SHALL be ignored.

#### Scenario: Back-to-back appointments are not padded twice
- **WHEN** a provider has appointments 12:00-13:00 and 13:00-14:00
- **THEN** the draft covers 11:45-14:15 as one interval

#### Scenario: No-show credit
- **WHEN** a booking is NO_SHOW at 15:00
- **THEN** the draft credits 14:45-15:15

#### Scenario: Multi-technician booking
- **WHEN** a booking has segments for two team members
- **THEN** only the segment of the provider's own team member, at its sequential offset, is used

### Requirement: One timecard per day, gaps are unpaid breaks

The draft SHALL be a single timecard per local work date from the first interval start to the last interval end. Every gap between merged intervals SHALL be an unpaid break regardless of length, because the technician is fully relieved of duty and free to leave. Time in such a gap SHALL NOT count as worked unless a finding states the law requires payment.

#### Scenario: Split shift
- **WHEN** a provider has intervals 07:45-14:15 and 17:15-20:15
- **THEN** one timecard 07:45-20:15 is proposed with an unpaid break 14:15-17:15 and 9.5 paid hours

### Requirement: Draft includes meal and rest breaks

The draft SHALL pre-place the unpaid meal and the required paid rests. The meal SHALL use the natural gap of at least 30 minutes that starts before the end of the 5th work hour and is closest to the 4 hour mark; if none exists the system SHALL NOT place a meal over a booked appointment and SHALL raise `MEAL_NOT_POSSIBLE`. Each required rest SHALL be added as a paid 10 minute break centered in its block, snapped to appointment padding when possible. The technician's approval SHALL count as her attestation of the breaks as shown, and an unticked required break SHALL become a finding.

#### Scenario: Day with a long gap
- **WHEN** a day has appointments 10:00-13:00 and 14:00-17:00
- **THEN** the 60 minute gap is the unpaid meal and one paid rest is added in each block that needs it

#### Scenario: Booked solid
- **WHEN** a day has 6.5 hours of back-to-back appointments
- **THEN** no meal is placed, `MEAL_NOT_POSSIBLE` is raised and two paid rests are pre-placed in padding time

### Requirement: Self-filled timecards are never overwritten

If a Square timecard for the provider and date already exists and was not created by this system (entered by the technician, a manager or the owner, including by clock-in and clock-out), the system SHALL NOT pre-fill, add breaks to, or write to that timecard. It SHALL show the timecard as it is, run the full policy on it, and ask the technician to approve it as is.

#### Scenario: Technician clocked in and out herself
- **WHEN** a timecard 12:00-18:30 without breaks exists in Square for yesterday
- **THEN** the system performs no Square write, shows the timecard with a link to it, raises `MEAL_MISSING` and `REST_MISSING` and asks for approval

### Requirement: California meal period findings

The system SHALL raise `MEAL_MISSING` when a work stretch exceeds 5 hours without an unpaid break of at least 30 minutes that started before the end of the 5th work hour. A gap after the 5th hour SHALL NOT cure the violation. If the day totals at most 6 hours and the day has a recorded waiver, the finding SHALL be `MEAL_WAIVED` (informational). Work over 10 hours SHALL require a second meal started before the end of the 10th hour, waivable only when the day totals at most 12 hours and the first meal was not waived. Each day with a non-waived violation SHALL carry a `MEAL_PREMIUM_OWED` of one hour at the regular rate, at most once per day.

#### Scenario: Six and a half hours with no meal
- **WHEN** a day has 6.5 continuous paid hours and no unpaid break
- **THEN** `MEAL_MISSING` and a premium of one hour at the day's rate are raised

#### Scenario: Six hour day with waiver
- **WHEN** a day totals exactly 6.0 hours, no meal, waiver recorded
- **THEN** `MEAL_WAIVED` is raised and no premium is owed

#### Scenario: Meal taken in time
- **WHEN** a 30 minute unpaid break starts after 3.5 worked hours of an 8 hour day
- **THEN** no meal finding is raised

### Requirement: California rest period findings

The system SHALL require paid 10 minute rests: 1 when day hours exceed 3.5 up to 6, 2 when over 6 up to 10, 3 when over 10 up to 14. Evidence SHALL be recorded paid rest breaks or technician confirmation. Missing evidence SHALL raise `REST_MISSING`; when the owner marks a rest as not provided, `REST_PREMIUM_OWED` of one hour at the regular rate is raised, at most once per day and separate from any meal premium. Rest breaks SHALL be paid time and SHALL NEVER reduce paid hours. A meal period SHALL NOT satisfy a rest requirement.

#### Scenario: Rests recorded
- **WHEN** a 6.5 hour day has two recorded paid rest breaks
- **THEN** no rest finding is raised and paid hours are unchanged

#### Scenario: Rest not evidenced
- **WHEN** a 5 hour day has no recorded rest and no technician confirmation
- **THEN** `REST_MISSING` is raised for managers

### Requirement: Overtime and double time

The system SHALL compute regular, overtime (x1.5) and double time (x2) hours per California rules: daily over 8 hours x1.5 and over 12 hours x2; weekly (Monday to Sunday) over 40 regular hours x1.5, without double counting daily overtime; on the seventh consecutive workday of a workweek the first 8 hours x1.5 and beyond x2.

#### Scenario: Separate overtime alerts
- **WHEN** a day has 9 hours
- **THEN** `OVERTIME_DAILY` is raised for 1 hour and no double time alert
- **WHEN** a day has 13 hours
- **THEN** `OVERTIME_DAILY` (4 hours) and `DOUBLE_TIME_DAILY` (1 hour) are raised as two distinct alerts

#### Scenario: Reference month
- **WHEN** the 23 October 2026 timecards (119.25 paid hours) are computed at $18.50
- **THEN** the result is 114.33 regular hours, 4.92 overtime hours, 0 double time hours and $2,251.60 gross wages before premiums

### Requirement: Split shift premium check

The system SHALL treat unpaid gaps over 60 minutes (configurable; shorter gaps are meal periods) as split-shift candidates and compare the day's total pay to the configured local minimum wage times (paid hours + 1). It SHALL raise `SPLIT_SHIFT_PREMIUM_OWED` for the shortfall (at most one hour of minimum wage) only if the pay is lower. A rate above local minimum wage SHALL produce no premium.

#### Scenario: Rate above minimum wage
- **WHEN** a split shift day is paid at $18.50 and the configured minimum wage is $17.75
- **THEN** no split shift premium is raised

### Requirement: Daily confirmation over SMS

For each W2 provider with payroll automation enabled the system SHALL, at 08:00 America/Los_Angeles, create a proposal for the previous local work date and send an SMS with a signed link valid 36 hours to https://salon.akluxnails.com/payroll/day/{token}. One reminder SHALL be sent at 16:00 if still unconfirmed. After 24 hours unconfirmed an escalation SMS digest SHALL go to each active MANAGER, at most once per day. Each message kind SHALL be sent at most once per proposal. Nothing SHALL be written to Square before confirmation.

#### Scenario: Confirmed before reminder
- **WHEN** the technician confirms at 09:30
- **THEN** the 16:00 reminder is not sent

#### Scenario: Escalation
- **WHEN** a proposal is still unconfirmed 24 hours after creation
- **THEN** managers receive one escalation SMS and the day shows as overdue in the UI

#### Scenario: Invalid or expired link
- **WHEN** the token is tampered with or expired
- **THEN** the page shows a neutral error and no data

### Requirement: Technician edits are re-validated on the server

The confirmation page SHALL let the technician change start and end, record meal start and end or mark the meal not taken, tick each required rest, and add a note. The server SHALL re-derive hours, run the policy and write the result; client-sent pay amounts SHALL be ignored. Missing meal or unevidenced rest SHALL NOT block confirmation but SHALL create findings.

#### Scenario: Attempted extension past the allowed window
- **WHEN** the technician submits an end time before the start time or beyond midnight
- **THEN** the request is rejected with a clear message and nothing is written

### Requirement: Square timecards are the source of truth

On confirmation the system SHALL create or update (using the stored version) the Square timecard with breaks and the effective wage, and store its id and version. A nightly and on-demand sync SHALL mirror timecards for the last 45 days. If a confirmed day changes in Square, its review status SHALL return to `NEEDS_REVIEW` and the UI SHALL say it changed in Square. A failed write SHALL leave the day `CONFIRMED_PENDING_WRITE` and be retried.

#### Scenario: Owner edits in Square
- **WHEN** the owner changes a confirmed timecard directly in Square
- **THEN** the next sync updates the mirror, recomputes findings and flags the day

#### Scenario: Square write restricted to labor resources
- **WHEN** any code attempts a non-GET Square call outside `/v2/labor/timecards` and `/v2/labor/break-types`
- **THEN** the client refuses the call

### Requirement: Highlighted exceptions for owners and managers

The UI at salon.akluxnails.com SHALL show a queue of pending and overdue confirmations and an exceptions panel that highlights: missing meal, meal premium owed, rest not evidenced, rest premium owed, overtime (8 to 12 hours) and double time (over 12 hours) as separate alerts, meal not possible, seventh day, timecard with no appointments, appointments with no timecard, large difference between timecard and appointments, cashew note on a W2 provider, and days changed in Square. Managers SHALL see hours and findings but not rates or dollar amounts. Owners SHALL see everything and SHALL be able to mark a rest as not provided or record a meal waiver, each audited.

#### Scenario: Missed meal highlighted
- **WHEN** a day has `MEAL_MISSING`
- **THEN** the day is visually flagged with the rule and the premium amount (owner only)

### Requirement: Pay period summary and export

For each half-month period the system SHALL produce per provider: regular, OT and DT hours, meal and rest premium hours and dollars, gross wages, tips (card tips with the existing processing fee rule, declared cash tips), per-day detail with findings, and a CSV export. Weekly overtime SHALL use whole Monday to Sunday weeks even when they span two periods. A period SHALL NOT be marked ready for payroll while it has unreviewed days or unresolved findings.

#### Scenario: Week spans two periods
- **WHEN** the week Monday 2026-10-12 to Sunday 2026-10-18 straddles the 15th and the period ending on the 15th is summarized
- **THEN** weekly overtime is computed over the whole Monday to Sunday week, and only the hours dated inside the period are attributed to it

### Requirement: Cash handling for W2 providers

For a provider whose type is W2 on the booking date, `cashew` notes SHALL be ignored and commission settlement lines SHALL NOT be generated. Bookings with a cash note on such a provider SHALL raise `CASH_NOTE_ON_W2`.

#### Scenario: Cash note on W2 provider
- **WHEN** a booking for a W2 provider carries note "cashew"
- **THEN** no cash service is credited to her and `CASH_NOTE_ON_W2` is shown

### Requirement: Auditability and retention

Every confirmation, edit, waiver, premium decision and on-behalf confirmation SHALL write an audit row with actor, time, before and after. Review, mirror and audit rows SHALL NOT be hard-deleted and SHALL be retained at least four years.

#### Scenario: Manager confirms on behalf
- **WHEN** a manager confirms an overdue day
- **THEN** status is `MANAGER_CONFIRMED` and an audit row names the manager

### Requirement: Per-worker payroll automation switch

The OWNER SHALL be able to enable or disable payroll automation per W2 provider. When disabled, the system SHALL create no proposals, send no SMS and write nothing to Square for that provider. Enabling SHALL apply from the next day and SHALL NOT back-fill.

#### Scenario: Automation off
- **WHEN** a W2 provider has automation disabled
- **THEN** no proposal or SMS is created for her at 08:00 PT

### Requirement: Low-volume, plain SMS

SMS SHALL be sent only for days with worked appointments or an existing Square timecard, at most 2 per worker per day, only between 08:00 and 20:00 America/Los_Angeles, one reminder at 16:00 PT at most, with several missed days collapsed into one message, as single-segment plain text containing a link of the form `https://salon.akluxnails.com/payroll/day/{token}`. STOP SHALL be honored.

#### Scenario: Day off
- **WHEN** a W2 provider had no appointments and no timecard yesterday
- **THEN** no SMS is sent

#### Scenario: Three unconfirmed days
- **WHEN** three days are unconfirmed at 16:00 PT
- **THEN** one SMS lists the days with one link per day or a link to the list

### Requirement: Reporting time and second report findings

The system SHALL raise `REPORTING_TIME_REVIEW` for a reported day with under 2 paid hours or with a NO_SHOW or late cancellation, and `SECOND_REPORT_MINIMUM` when a second work block of the day has under 2 hours. These SHALL be owner decisions and SHALL NOT add pay automatically.

#### Scenario: Short day
- **WHEN** a provider has a day with 1.33 paid hours
- **THEN** `REPORTING_TIME_REVIEW` is raised for the owner

### Requirement: Tips for W2 providers are paid in full

For W2 providers card tips SHALL be reported in full with no card processing fee deduction, and tips SHALL NOT count toward minimum wage or the regular rate.

#### Scenario: Card tip
- **WHEN** a W2 provider receives a $20.00 card tip
- **THEN** the pay summary shows $20.00, not a fee-reduced amount

### Requirement: Premium line items

Meal, rest and split shift premiums SHALL be shown as separate lines per day and per period in the summary and export, so they can be paid on the regular payday and shown on the wage statement.

#### Scenario: Premium export
- **WHEN** a period contains a meal premium
- **THEN** the export lists it as a distinct earning line with the date and amount

### Requirement: SMS disabled by default

SMS SHALL be sent only when both the global setting and the provider's `sms_enabled` flag are on; both SHALL default to off. With SMS off, proposals SHALL still be created and shown in the owner and manager queue with a copyable signed link.

#### Scenario: Default configuration
- **WHEN** a W2 provider is created with default settings
- **THEN** proposals are created at 08:00 PT and no SMS is sent
