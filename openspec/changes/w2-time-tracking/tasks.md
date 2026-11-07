## 0. Preparation

- [ ] 0.1 Owner answers the open questions in design.md (technician mobile numbers, payroll export layout, waiver form, who is "managers" for escalation)
- [ ] 0.2 Update `openspec/config.yaml` context: Square is read-only EXCEPT Labor timecards and break types; next migration V181
- [x] 0.3 Compliance research done, see compliance.md (sources, per-rule handling)
- [ ] 0.4 CPA or counsel one-time review of items flagged in compliance.md (waiver form, reporting time, split shift for client-booked gaps, premium payroll line)
- [ ] 0.5 Written policies for the owner: free-to-leave gaps, meal and rest policy, meal waiver form for days up to 6 hours, scheduling policy for reporting time

## 1. Pure domain (no I/O)

- [ ] 1.1 `CaWageRules` record (thresholds, minimum wage, premium rules) and `CaWagePolicy` with findings enum and day/week computation
- [ ] 1.2 `TimecardDraftCalculator` (segments to intervals, NO_SHOW, cancelled, merge, gaps as unpaid breaks, midnight and DST handling)
- [ ] 1.2a Break placement: natural-gap meal, `MEAL_NOT_POSSIBLE`, rest centering and padding snap, unticked break to finding
- [ ] 1.3 Golden tests from Yana's October 2026 data (fixtures from the booking mirror): 119.25 h, 114.33 regular, 4.92 OT, gross $2,251.60, meal findings 10/8 and 10/30, 10/25 with and without waiver, rest findings with and without recorded rests, 10/31 second meal
- [ ] 1.3a Tests: reporting time review, second report minimum, split shift premium (10/7 example about $12.88, 10/8 covered by overtime)
- [ ] 1.4 Edge tests: week crossing a period boundary, 7th consecutive day, over 12 hours, split shift premium check, unpaid gap not curing a meal

## 2. Persistence (migration V181)

- [ ] 2.1 `V181__w2_time_tracking.sql`: `provider_employment`, `square_timecard_mirror`, `timecard_review`, `timecard_audit`, `timecard_reminder` (business_id on every table, indexes, uniques as in D5)
- [ ] 2.2 Entities and business-scoped repositories (`findByIdAndBusinessId` for every caller-controlled id)
- [ ] 2.2b `sms_enabled` flag (default false) and global `timecards.sms.enabled` (default false); sender checks both
- [ ] 2.2a `payroll_automation_enabled` and `alerts_enabled` flags on `provider_employment`, owner toggle endpoint and UI
- [ ] 2.3 Seed Yana: W2, $18.50 effective 2026-10-01 (owner-run, not a migration)

## 3. Square Labor

- [ ] 3.1 `SquareLaborClient` with path allow-list, search, create, update, break types; fixture-based tests
- [ ] 3.2 `TimecardSyncService` (rolling 45 days, nightly job zone America/Los_Angeles, on-demand), drift detection, retry job for `CONFIRMED_PENDING_WRITE`
- [ ] 3.3 Break type bootstrap ("Unpaid break", "Paid rest 10 min") per location

## 4. Confirmation loop

- [ ] 4.0 Detect externally entered timecards (not owned by a `timecard_review`) and create `EXTERNAL` reviews with no pre-fill and no write
- [ ] 4.1 `TimecardProposalService` and 08:00 PT scheduler (idempotent per provider and date)
- [ ] 4.2 `TimecardLinkSigner` (HMAC, 36 h expiry) modeled on `CheckoutReviewRatingSigner`
- [ ] 4.3 Reminder and escalation scheduler (08:00 SMS, one 16:00 reminder, 24 h escalation digest, 2 per day cap, quiet hours, collapse multi-day, no SMS on days off) using `TwilioSmsService`, deduped through `timecard_reminder`
- [ ] 4.4 `TimecardConfirmService`: re-derive, validate, run policy, write to Square, audit
- [ ] 4.5 Provider mobile number capture on the employment profile (owner screen)

## 5. Web and security

- [ ] 5.1 `TimecardController` (`/api/timecards/**` OWNER+MANAGER, `/api/timecards/me/**` PROVIDER, `/api/public/timecards/confirm/{token}` signed and rate limited)
- [ ] 5.2 `SecurityConfig` ordered matchers; DTOs hide rates and dollars from MANAGER
- [ ] 5.3 `PayPeriodSummaryService` and CSV export endpoint (OWNER)
- [ ] 5.3a Tips for W2 paid in full (no processing fee deduction); premium lines and PDF summary in export
- [ ] 5.4 Cash handling: skip cashew notes for W2 in `SquareMonthAggregator`, `CASH_NOTE_ON_W2` finding

## 6. Frontend (salon.akluxnails.com)

- [ ] 6.1 Proxy routes under `app/api/timecards/*`, `api.ts` and `serverApi.ts` additions, types
- [ ] 6.2 Public "Yesterday" confirmation page (mobile first): proposal, appointments as reference, meal and rest inputs, note, submit
- [ ] 6.3 `/timecards` manager and owner page: queue, exceptions panel with highlights (overtime and double time as separate alerts, missing meal, missing rests, meal not possible), day detail with findings and audit trail
- [ ] 6.4 Pay period summary page with export and "ready for payroll"
- [ ] 6.5 Owner screen for employment profile, rate history, mobile numbers
- [ ] 6.6 Nav entries (AdminMenu for owner, reports and manager menu for managers)

## 7. Verification and rollout

- [ ] 7.1 Full backend suite and frontend tsc and build
- [ ] 7.2 Localhost run against the real salon: create then delete a test timecard (rollback rule), compare mirror with existing October timecards, dry-run proposals for the last 7 days without sending SMS
- [ ] 7.3 Rollout in stages behind a per-business flag: (a) policy and summary read-only on existing timecards, (b) proposals visible to owner only, (c) SMS to Yana, (d) escalation to managers
- [ ] 7.4 After a full month, review findings volume with the owner and tune reminders
- [ ] 7.5 Ideas parked: put meals and rests into the technicians' Square calendar or schedule; cash drawer reconciliation, minimum hourly plus KPI bonus, clock in and out from a device
