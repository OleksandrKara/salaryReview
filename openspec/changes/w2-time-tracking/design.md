## Context

salaryReview computes commission payroll from Square mirrors (bookings, orders, payments). A `Provider` links to Square team member ids; an `AppUser` (role PROVIDER) links to a provider. Twilio SMS (`TwilioSmsService`, inbound controller, reply flows) and Scheduled jobs (zone America/Los_Angeles) exist. `ManagerTimeService` already tracks manager hours locally and never touches Square. The Square credential (encrypted in `square_connection`) was verified in Nov 2026 to carry Labor timecard read and write scopes. Next Flyway migration is **V181** (latest V180).

## Goals / Non-Goals

Goals: Square as source of truth for hours; deterministic, explainable derivation from appointments; every California premium surfaced as a finding with the rule that produced it; minimal daily effort for technicians; auditable edits.
Non-goals: see proposal.

## Decisions

**D1 - Square timecards are the truth; the app keeps only what Square cannot hold.** Local tables: `provider_employment` (profile and rate history), `square_timecard_mirror` (read mirror), `timecard_review` (one row per provider and work date: status, confirmation actor and time, meal waiver, notes, link to Square timecard id and version), `timecard_audit` (before and after JSON of every edit). Hours and breaks are read from the mirror, never from `timecard_review`. A direct edit in Square (owner or manager) wins and is picked up by the next mirror pass; if a confirmed day changes in Square after confirmation, the day returns to `NEEDS_REVIEW` with a visible "changed in Square" flag.

**D2 - Narrow relaxation of the Square read-only rule.** A new `SquareLaborClient` (separate class, not `SquareClient`) is the only code allowed to issue non-GET calls, and only for `/v2/labor/timecards*` and `/v2/labor/break-types*`. Enforced by a path allow-list unit test and a guard in the client. `openspec/config.yaml` context is updated to say "read-only except Labor timecards and break types". Writes use idempotency keys where Square supports them and optimistic `version` on update. The credential comes from `SquareClientProvider` (decrypted in memory, never logged).

**D3 - Draft derivation is a pure function.** `TimecardDraftCalculator.derive(DayInput) -> DraftDay` where input is the technician's own segments (each booking's `appointment_segments_json` with sequential offsets, only segments whose `teamMemberId` belongs to the provider) and booking status.
- Interval per segment = [start - 15 min, end + 15 min]; merge overlapping and touching intervals.
- NO_SHOW: interval [start - 15, start + 15] (30 min credit).
- CANCELLED_BY_SELLER and other cancelled statuses: no time.
- Output: one `DraftTimecard` per day from first start to last end; every gap becomes an unpaid break (type "Unpaid break"). A gap of any length is unpaid because the technician is free to leave (owner-confirmed rule, reaffirmed for split shifts such as 10/8 14:15-17:15). A gap never turns into paid time unless the law requires it (see D5 split shift note).
- Time zone: salon local (America/Los_Angeles); work date = local date of the first start. Appointments crossing midnight are split by local day.

**D3a - Pre-filled breaks.** The draft also places breaks, so the technician only approves.
- Meal: if a gap between appointments is at least 30 minutes and starts before the end of the 5th work hour, the draft uses the gap closest to the 4 hour mark as the unpaid meal (it is already an unpaid break). If no such gap exists the day is booked solid: the draft cannot invent a meal over a booked appointment, so it shows `MEAL_NOT_POSSIBLE` (managers see the premium that would be owed and can fix the schedule). The system never moves appointments.
- Rests: the draft adds one paid 10 minute rest per required block (1, 2 or 3 by day hours), centered in each block and snapped to the 15 minute padding around appointments when possible so it never overlaps a booked service. Rests are paid, so hours do not change.
- The technician's approval of the draft is her attestation that meal and rests were taken as shown; she can untick or move any break, and an unticked required break becomes a finding.
- The draft is shown in our UI first. It is written to Square only after approval.

**D3b - Self-filled days are never overwritten.** At proposal time the sync looks up Square timecards for the provider and date. If one exists that is not owned by a `timecard_review` row (entered by the technician, a manager or the owner in Square, including a clock-in and clock-out) the system creates a review in mode `EXTERNAL`: no draft, no Square write, no auto-added breaks. The page shows the timecard as it is in Square (with a link to it), shows the pre-fill the system would have suggested only as a reference, runs the full policy, and asks for approval of the timecard as is. Approval only sets the review status; edits are made by her in Square (or by a manager) and picked up by the next sync.

**D4 - California policy engine (`CaWagePolicy`, pure).** Input: day timecards plus waivers, rate, week context (Mon-Sun, `workweek-configs` start MON). Findings (each with code, date, severity, human text, premium amount):
- `MEAL_MISSING`: a continuous work stretch passes 5 hours without an unpaid break of at least 30 minutes that STARTED before the end of the 5th work hour. A gap between appointments after the 5th hour does not cure it. Exception: day total at most 6 hours AND a recorded mutual waiver on `timecard_review.meal_waiver` -> `MEAL_WAIVED` (info). Second meal: work over 10 hours needs a second 30 minute meal started before the end of the 10th hour; waivable only if day total at most 12 hours and the first meal was not waived.
- `MEAL_PREMIUM_OWED`: one hour at the regular rate per day with any non-waived meal violation (at most one per day).
- `REST_MISSING`: required paid 10 minute rests = 1 for more than 3.5 up to 6 hours, 2 for more than 6 up to 10, 3 for more than 10 up to 14 (per day total). Evidence = recorded paid rest breaks (break type "Paid rest 10 min") or technician confirmation in the Yesterday page. Missing evidence -> finding; `REST_PREMIUM_OWED` is one hour per day (separate from the meal premium) once the owner marks the rest as not provided.
- Rest breaks are paid time and are NEVER deducted from hours. Meal breaks are unpaid only when the worker is fully relieved of duty.
- Overtime alerts (separate codes): `OVERTIME_DAILY` for hours over 8 up to 12 (x1.5), `DOUBLE_TIME_DAILY` for hours over 12 (x2), `OVERTIME_WEEKLY` for over 40 in the week, `SEVENTH_DAY`. Rules: per day over 8 hours x1.5, over 12 hours x2; per week (Mon-Sun) over 40 regular hours x1.5; seventh consecutive day in a workweek: first 8 hours x1.5, beyond x2. Daily OT hours are not counted again toward weekly OT.
- Premium pay is paid at the regular rate of compensation (hourly rate plus any non-discretionary pay; tips excluded). Premiums are wages and reported on their own line.
- Reporting time (Wage Order 2 s.5): `REPORTING_TIME_REVIEW` for reported days under 2 paid hours or with NO_SHOW or late cancellation, `SECOND_REPORT_MINIMUM` for a second block under 2 hours; owner decides, never automatic.
- Split shift (gaps over 60 minutes, configurable; shorter gaps are treated as meal periods): a gap in which the worker is free to leave is not worked time. A split-shift premium can apply only when pay is near minimum wage; the policy compares total daily pay to minimum wage times hours plus one hour, using the configured local minimum wage (San Diego 2026: $17.75). Rate above minimum wage means no premium; the check stays in code so a later rate or minimum wage change raises a finding automatically.
The policy text is configurable data (thresholds in a `CaWageRules` record), not scattered literals.

**D5 - Data model (V181).**
- `provider_employment(id, business_id, provider_id, employment_type, hourly_rate_cents, effective_from, effective_to null, mobile_phone null, created_at)`; query the row effective on the work date.
- `square_timecard_mirror(id, business_id, square_timecard_id unique per business, provider_id, work_date, start_at, end_at, timezone, hourly_rate_cents, breaks_json, status, square_version, synced_at)`.
- `timecard_review(id, business_id, provider_id, work_date, mode [DRAFT|EXTERNAL], status [PROPOSED|CONFIRMED|NEEDS_REVIEW|MANAGER_CONFIRMED], proposed_json, confirmed_at, confirmed_by_user_id, meal_waiver boolean, rest_confirmed boolean, note, square_timecard_id, square_version; UNIQUE(business_id, provider_id, work_date))`.
- `timecard_audit(id, business_id, review_id, actor, action, before_json, after_json, at)`.
- `timecard_reminder(id, business_id, review_id, kind [INITIAL|R10|R16|ESCALATION], twilio_sid, sent_at)` for idempotent SMS.
All repository lookups by caller-supplied ids are business-scoped (`findByIdAndBusinessId`).

**D6 - Confirmation loop.** A scheduled job at 08:00 PT (zone America/Los_Angeles) builds proposals for yesterday for each W2 provider with `payroll_automation_enabled` and at least one worked appointment or an existing Square timecard that day (no SMS for days off). The SMS points to `https://salon.akluxnails.com/payroll/day/{token}`, the payroll page for that exact day in draft state with everything pre-filled. It sends one SMS with a signed link to the draft and a link to her timecard in Square (Square Dashboard URL pattern to be verified during implementation, fallback to our own day page) (HMAC token carrying business, provider, work date, expiry 36 h; modeled on `CheckoutReviewRatingSigner`), and at most one reminder at 16:00 PT if still unconfirmed (no 10:00 reminder). Several missed days collapse into ONE message listing the days. Per worker cap: 2 SMS per day. No SMS before 08:00 or after 20:00 PT; Twilio STOP is honored. Managers get an in-app overdue list; one escalation SMS to MANAGER users only after 24 hours unconfirmed and at most once per day as a digest; owner and managers can confirm on behalf (`MANAGER_CONFIRMED`, audited). Twilio sends respect existing SMS policy: this is transactional staff messaging, not marketing, so marketing consent does not apply. Sends are idempotent via `timecard_reminder`. No timecard is written to Square before confirmation. Deadline: 12:00 PT next day.
The Yesterday page (no login, signed link, rate limited) shows the proposed day with appointments as reference rows, lets the technician: adjust start and end (never below zero length; cannot extend past midnight), record the meal (start, end) or mark it not taken, mark each required rest as taken (default unchecked; no auto-assumption), and add a note. The server re-derives and re-runs `CaWagePolicy` on every submit; the client never sends computed pay. A missed meal or unevidenced rest does not block confirmation; it creates findings for managers.

**D6a - Per-worker automation switch.** `provider_employment.payroll_automation_enabled` (OWNER only, default false). Off means no proposals, no SMS, no Square writes for that worker; the mirror and policy alerts can still run read-only (separate `alerts_enabled`, default true for W2). Turning it on starts from the next day and never back-fills.

**D6c - SMS is off by default.** Two gates, both default false: global property `timecards.sms.enabled` and per-worker `provider_employment.sms_enabled`. Nothing is sent unless both are on. With SMS off the loop still works in the app: the proposal is created at 08:00 PT, appears in the owner and manager queue, and the signed day link is shown and can be copied or sent by hand; the technician can also open it from the app. Turning SMS on needs no code change.

**D6b - SMS cost.** Twilio US outbound is about $0.0083 per segment plus about $0.004 toll-free carrier fee, roughly $0.0125 per single-segment message. Messages are kept under 160 GSM-7 characters. One SMS per worked day is about 22 per month per worker, about $0.28; with one reminder on a bad day about $0.40. Five workers about $1.40 to $2.00 per month; even 31 days per worker is about $0.39 each, $1.94 for five. Cost is not a design driver, spam is, hence the caps above. Actual cost is read from Twilio price data if available; otherwise estimated.

**D7 - Square sync.** `TimecardSyncService` reads `searchTimecards` for a rolling 45 day window nightly and on demand, upserts the mirror, detects deletions (soft status), and flags drift (day confirmed but Square changed). Write path: create on first confirm (store id and version), update with version on re-confirm. Break types ("Unpaid break", "Paid rest 10 min") are created once per location and cached. Team member to provider mapping reuses `Provider.squareTeamMemberIds`. Failure to write leaves the review `CONFIRMED_PENDING_WRITE` with retry (exponential backoff job) and a visible banner.

**D8 - Pay period summary.** `PayPeriodSummaryService` for the existing halves (1-15, 16-end). Weekly overtime needs the full Mon-Sun week even when it spans two periods, so the service loads the adjacent days. Output per provider: regular, OT, DT hours; meal and rest premiums; gross wages at the effective rate; tips (Square card tips from the payments mirror paid in full: for W2 providers NO processing fee deduction, Labor Code 351; declared cash tips); per day detail with findings; CSV export in the layout the payroll provider needs (hours by day, hours and dollars by earning code). Unresolved findings block the "ready for payroll" mark but never change numbers silently.

**D9 - Cash handling for W2.** `SquareMonthAggregator` and `CashNoteParser` skip cashew notes for providers whose employment type is `W2` on the date. The owner is told in the UI that W2 cash services must be closed with cash checkout. A finding `CASH_NOTE_ON_W2` flags such bookings.

**D10 - Security and roles.** New endpoints under `/api/timecards/**` = OWNER and MANAGER; `/api/timecards/me/**` = PROVIDER (own data only); public `/api/public/timecards/confirm/{token}` (signed token) with strict rate limiting and no PII beyond the technician's own day. Rate and pay data visible to OWNER only (managers see hours and findings, not dollars). Employment and rate management = OWNER.

**D11 - Retention.** Audit and mirror rows are kept at least four years (California wage records requirement is at least three; confirm with the accountant). No hard deletes of review or audit rows.

See `compliance.md` for the rule-by-rule review and sources (nothing here is a licensed attorney's opinion; CPA or counsel should confirm the flagged items).

## Risks and open questions

- Legal reading of rules (waiver form, reporting time, split shift for client-booked gaps, premium payroll line item) should be confirmed once by the CPA or counsel; the policy lives in one class so changes are cheap. See compliance.md.
- Rest breaks are self-reported; the system records the technician's confirmation and the owner's decision, it cannot prove them.
- Square write scope expiry or revocation: the sync shows a clear "Square write access lost" state and falls back to proposals only.
- Mobile numbers for technicians are new data (not on `Provider`/`AppUser` today).
- Technician approves through a signed link on salon.akluxnails.com (no login), which exposes only her own day; revisit if an SMS number changes hands. Owner confirmed this approach on 2026-11-03.
- Payroll provider is Patriot (no API). The CPA accepts a PDF, so the first export is a CSV plus a printable PDF summary; layout will be refined when the CPA asks for details.
