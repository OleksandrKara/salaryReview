## Why

The salon is moving its first technician (Yana Kovalenko) from commission to W-2 hourly pay, with more to follow. Hours must be captured accurately and in line with California wage and hour rules (meal and rest breaks, daily and weekly overtime, premium pay). Today the technician fills a timecard by hand in Patriot Payroll, which has no API. The first month (Oct 2026) showed the typical failures: missed 30 minute meals, rests not recorded, split shifts counted as worked time, and manual timecards that disagree with the appointment book.

Square already holds the facts that drive hours (every appointment, including walk-ins, is in the system) and has a Labor API (timecards, break types). This change makes **Square timecards the source of truth** and builds the process around it: the system proposes yesterday's timecard from appointments, the technician confirms by SMS link, managers see exceptions, and the owner gets a payroll-ready summary with every California premium computed.

## What Changes

- Per-provider employment profile: `COMMISSION` (today's behavior) or `W2` with an hourly rate history (effective-dated), a mobile number for SMS, and W2 start date. Yana is W2 from 2026-10-01 at $18.50/h.
- `TimecardDraftCalculator`: derives a draft day from the technician's own appointment segments. Paid time = union of [start - 15 min, end + 15 min]; NO_SHOW credits 30 min; cancelled by seller is not paid; any gap between appointments is an unpaid break inside ONE timecard per day (the technician is fully relieved of duty and free to leave).
- `CaWagePolicy` (pure, unit tested): meal period, rest period, daily and weekly overtime, double time, seventh consecutive day, meal and rest premium pay. Output is a list of typed findings, never silent adjustments.
- No clock-in and clock-out habit: the technician never starts or stops a clock. Each morning the system pre-fills the whole previous day for her, including the 30 minute unpaid meal and the 10 minute paid rests, and sends a link to the draft and to her timecard in Square. She approves (one tap) or edits. If she or a manager already entered a timecard in Square for that day, the system does NOT pre-fill or write anything: it shows that timecard as is, runs all alerts on it, and asks for approval.
- Daily confirmation loop over SMS (Twilio): next morning a signed link to the payroll page for that day on salon.akluxnails.com; technician confirms or edits start, end, meal and rest breaks; on confirm the system writes the timecard to Square. One reminder at 16:00 PT, deadline 12:00 PT the next day, escalation digest to managers after 24 h.
- Square Labor writes (timecards and break types only), with a nightly and on-demand read mirror so edits the owner makes directly in Square are respected.
- SMS is OFF by default (global setting and per worker); proposals and signed links still appear in the app queue.
- Per-worker payroll automation on/off switch (owner), low-volume SMS (one per worked day, one reminder at most, no days off), link to https://salon.akluxnails.com/payroll/day/{token}. SMS cost is about $0.0125 per message, about $0.30 to $0.40 per worker per month.
- California compliance review (compliance.md) added to scope: reporting time pay, split shift premium, tips paid in full for W2, premium lines, retention.
- Owner and manager UI on salon.akluxnails.com: confirmation queue, **highlighted exceptions** (missed meal, meal premium owed, rest not evidenced, overtime 8-12 h and double time over 12 h as two separate alerts, 7th day, timecard without appointments, appointments without timecard), and a pay period summary (regular, OT, DT hours, premiums, gross, tips) exportable for the payroll provider.
- For W2 providers, `cashew` notes are ignored and cash is expected as cash checkout (Square tender CASH). Commission settlement lines are not produced for W2 providers.

## Capabilities

### New Capabilities
- `w2-time-tracking`: employment profile, draft derivation, California compliance findings, confirmation loop, Square timecard sync, exceptions UI, pay period summary.

### Modified Capabilities
- Square integration rule "strictly read-only" is relaxed for ONE resource family: Labor `timecards` and `break-types`. All money objects (orders, payments, invoices, catalog, customers, bookings) stay read-only.
- Settlement and commission: W2 providers are excluded from commission settlement and cash-note handling.

## Non-goals

- Running payroll, tax filing, wage statements or direct deposit (the payroll provider keeps doing that; we export hours and premiums).
- Legal advice. The rules are the project's reading of California law, documented with sources in compliance.md; the flagged items should be confirmed once by the owner's CPA or employment counsel.
- Cash drawer reconciliation, KPI or bonus pay, minimum-hourly-plus-bonus models (recorded as ideas for later).
- Technician clock-in and clock-out (hours are derived from appointments and approved after the fact; managers can still use the existing manager time feature).
- Configuring Square so meals and rests appear in the technicians' Square calendar or schedule (later; for now the system pre-fills and alerts).
- Changing commission technicians in any way.
- Any write to Square other than Labor timecards and break types.

## Verification

- Golden-file unit tests built from Yana's real October 2026 data: 23 timecards, 119.25 paid hours, 114.33 regular and 4.92 OT hours, gross $2,251.60 at $18.50 (before premiums), meal premium findings on 10/8 and 10/30 (10/25 only without a waiver), rest findings absent once rests are recorded.
- Calculator tests for NO_SHOW, cancelled, back-to-back appointments, multi-segment bookings with offsets, midnight boundaries, DST days (America/Los_Angeles).
- Square Labor client tested against recorded fixtures; one localhost run against the real salon that creates and then deletes a test timecard (test data rollback rule), and read-only mirror comparison against the existing Oct timecards.
- CI: full backend suite and frontend tsc/build; business-scoping tests for every new repository method.
