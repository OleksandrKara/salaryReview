## Context

Builds on `sms-automation-platform`/`sms-automations-hub`'s existing infrastructure —
`SmsAutomationService`/`SmsAutomationRegistry` (enable/disable + registry, no frontend change
needed to expose a new automation), `SmsMessageLogService` (the `sms_message` activity log both
`/owner/automations` and `/admin/messages` already render), `TwilioSmsService`/`TwilioSmsClient`
(sending), and `TwilioInboundSmsController` (any reply lands in the shared `/admin/messages`
conversation thread automatically — this automation adds no new reply-parsing branch, unlike
`checkout_review_request`'s 1–5 rating logic).

**Revised approach (2026-08-06):** this design now follows `same_day_rebooking_discount`'s shape —
consent-branched message, click-tracked signed promo link, discount applied on click regardless of
which SMS variant the customer got — rather than `lead_follow_up`'s plain-text-no-link shape the
first draft used. See D8 for the reasoning and what changed.

The motivating numbers (from a direct production-data review, 2026-08-06, query against
`provider_visit`): 906 distinct customers all-time, **542 (59.8%) with exactly one visit**, median
gap between a repeat customer's visits **28 days** (IQR 21–35). No existing automation's targeting
logic covers this segment — confirmed by reading all four live schedulers' eligibility queries.

**Migration**: next Flyway version is `V68` (last is `V67__sms_reply_flow_square_customer_id.sql`).
**Security**: no new authenticated endpoints — the existing internal group-enroll endpoint gains an
optional parameter (see D9), still internal-auth-gated exactly as today. No `SecurityConfig` change.
**Cross-repo**: like `same-day-rebooking-discount`, this change touches both salaryReview (backend)
and akluxnails-home (promo verification/banner/booking-create) — see D9's file list.

## Goals / Non-Goals

**Goals**: (1) once a day, find customers whose only-ever visit was 21–35 days ago and who haven't
already rebooked on their own; (2) send exactly one message per customer, ever, carrying a $5
same-day-only coupon link — worded as an explicit offer for consented customers, worded as a plain
"come back" nudge with no discount language for everyone else; (3) reuse existing sending/logging/
consent/promo-link conventions rather than inventing new ones; (4) ship disabled by default, same as
every prior automation.

**Non-goals**: fixing `same_day_rebooking_discount`'s own conversion problem (parallel, ongoing
investigation — see Risks); generalizing to "any overdue repeat customer" (a stated future
direction, not this change); a new conversion-tracking dashboard (see Risks); touching the existing
$10 same-day-rebooking discount group/config in any way.

## Decisions

### D1: Daily scheduled run, not a 15-second poll

**Decision**: every other automation in this codebase (`SmsReplyFlowScheduler`,
`LeadFollowUpScheduler`, `SameDayRebookingScheduler`) runs on `@Scheduled(fixedDelay = 15_000)`
because each is chasing a tight, minutes-scale deadline (2 minutes after contact capture, a few
hours after checkout). This automation's eligibility window is **15 days wide** (21–35 days after a
visit) — there is no benefit to checking every 15 seconds, only extra `SquareClient` load for
customers who were already checked hours ago and haven't changed state. Use a once-daily cron
instead: `@Scheduled(cron = "0 0 10 * * *")` (10:00 AM server time — a reasonable hour to be sending
outbound SMS, away from early-morning/late-night edges), guarded by the same `@SchedulerLock`
distributed-lock convention every `@Scheduled` job in this codebase now uses.

**Consequence**: "21–35 days" becomes "21–35 days as measured at whichever day's 10 AM run first
sees the visit crossing the 21-day mark," i.e. a customer whose visit turns 21 days old at 3 PM
gets picked up the *next* morning, not same-day. That's an acceptable, non-harmful few-hour-to-a-day
slop given the window is 15 days wide to begin with — nowhere near the precision concern
`LeadFollowUpScheduler`'s design.md had to reason carefully about for its 2-minute window.

### D2: Eligibility source is `provider_visit`, not `marketing.contacts`

**Decision**: this campaign targets people who actually visited and paid — the same 542-customer
population the profit report measured — not marketing leads. New native-SQL repository query
(same convention as `MarketingContactsRepository.findPendingFollowUp`'s raw SQL):

```sql
SELECT customer_id, MIN(service_date) AS only_visit_date, MIN(provider_name) AS technician_name
FROM provider_visit
WHERE customer_id IS NOT NULL
GROUP BY customer_id
HAVING COUNT(*) = 1
   AND MIN(service_date) BETWEEN (CURRENT_DATE - INTERVAL '35 days')
                              AND (CURRENT_DATE - INTERVAL '21 days')
   AND NOT EXISTS (
       SELECT 1 FROM lapsed_customer_winback_send w
       WHERE w.square_customer_id = provider_visit.customer_id
   )
```

Run once per scheduled tick, result set bounded by the 15-day window (not the whole table), so cost
stays low regardless of how large `provider_visit` grows. **`technician_name` is pulled straight out
of this same query** (see D5/D8) — since `HAVING COUNT(*) = 1` guarantees exactly one row per
customer, that row's own `provider_name` unambiguously is "the technician from their one visit," no
separate resolution step needed.

### D3: Known data-boundary — `provider_visit` history starts 2025-07-01

**Decision (accepted limitation, not fixed here)**: the ledger this query reads from only goes back
to 2025-07-01 (confirmed during the profit-optimization review). A customer whose *only tracked* row
is really their second-or-later real visit — because their true first visit predates the ledger —
will be miscategorized as "first-time" and get this nudge anyway. Accepted: the false-positive cost
is low, and the ledger's coverage only improves going forward. Not a blocker for shipping this
change.

### D4: New table `lapsed_customer_winback_send` — one row per customer, ever

```sql
CREATE TABLE lapsed_customer_winback_send (
    id                 BIGSERIAL PRIMARY KEY,
    square_customer_id TEXT        NOT NULL,
    phone_number       TEXT,
    customer_name      TEXT,
    visit_date         DATE        NOT NULL,
    promo_expires_at   TIMESTAMPTZ,  -- only set for state = SENT, see below
    state              TEXT        NOT NULL CHECK (state IN
        ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NEGATIVE_FEEDBACK', 'SKIPPED_UNRESOLVED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ON lapsed_customer_winback_send (square_customer_id);
```

**`promo_expires_at`** (new since the first draft — see D8) records the exact coupon deadline shown
to (or silently honored for) this customer, computed once at send time — needed because unlike
`same_day_rebooking_send`, this table has no separate "trigger" row to hang the expiry off; it's
computed inline by the scheduler itself. Nullable: only meaningful for `state = SENT` — no coupon
link is ever generated for a `SKIPPED_*` row, so there's nothing to record.

**Rationale for the unique-per-customer (not per-visit) constraint**: this is framed as a single
"we miss you" nudge, matching the proposal's non-goal of *not* becoming a recurring campaign against
the same person. Once a customer has a row here — sent or skipped — they're never reconsidered by
this automation again, even if they later become a "one-visit, 21–35-days-lapsed" customer a second
time under a different visit.

### D5: Customer phone/name resolved via Square's Customer API; technician name comes straight from `provider_visit` (no separate resolution)

**Decision**: two different names appear in the copy (D8), resolved two different ways —

- **Customer phone number and given name**: `provider_visit` carries only a `customer_id`, no
  contact info, so these are resolved via `SquareClient` (`customerGivenNames`/
  `fetchCustomer(...).phoneNumber`, same accessors `MarketingContactsService` already uses
  elsewhere). If no phone number resolves, write `SKIPPED_UNRESOLVED` rather than silently doing
  nothing — otherwise an unresolvable customer would be re-looked-up by every single daily run for
  the rest of the 15-day window, since D4's `NOT EXISTS` check is the only thing that stops
  reprocessing.
- **Technician name**: comes directly out of D2's own query (`technician_name`) — no
  `TechnicianNameResolver`/live-Square-lookup needed, unlike `same_day_rebooking_discount`. That
  automation needs live resolution because "most recent visit" can change between trigger and send
  time; here, `HAVING COUNT(*) = 1` already guarantees there's exactly one visit, so its own
  `provider_name` unambiguously *is* "the technician," no ambiguity to resolve. Simpler by
  construction, not by omission.
- If `technician_name` is null/blank for the one visit (a data-quality edge case — `provider_visit`
  rows are expected to always carry a name, but this isn't unconditionally guaranteed), fall back to
  a technician-less copy variant (see D8) rather than sending a broken `{technician}` placeholder.

### D6: Live upcoming-appointment check before sending

**Decision**: reuse the same `hasUpcomingAppointment`-shaped check `LeadFollowUpScheduler`/
`SameDayRebookingScheduler` both already use — `SquareClient.bookingsForCustomer(customerId, now)`
filtered to `SquareBookingFilters.didHappen` and a future `startAt`. A customer who already rebooked
on their own sometime in the 21–35 day window shouldn't get a "we miss you, here's $5" text for an
appointment they've already made — write `SKIPPED_BOOKED`, no send.

### D7: Excludes anyone who has ever left negative feedback

**Decision**: reuse `SmsMessageLogService.hasNegativeFeedback(phoneNumber)`, the same guard
`SameDayRebookingScheduler` already applies — a customer who rated their visit poorly is never
re-approached with any "come back" message, regardless of which automation, regardless of how long
ago. Write `SKIPPED_NEGATIVE_FEEDBACK`.

### D8: A $5, same-day-only coupon — consent-branched wording, one shared click-tracked link (revised from the first draft's "no link, no incentive")

**Decision**: mirror `same_day_rebooking_discount`'s exact mechanism — the discount is applied when
the customer *clicks and books before it expires*, independent of what the SMS text itself says.
That separation is what makes both variants compliant: the marketing variant states the offer
outright; the transactional variant is a plain, functional link with no promotional claim in the
message, so it stays gated on nothing but a resolvable phone number.

- **Marketing (consented)** — checked the same dual-source way `SameDayRebookingScheduler.hasConsent`
  already does (`SmsConsentRepository.hasMarketingConsent` OR Square's own consent-segment
  membership via `RebookingProperties.getConsentSegmentId()`, no new segment needed, reuses the
  existing one). **Final copy, confirmed with the owner (2026-08-06):**
  > "Hi {name}! It's Lucy from AK.LUX.NAILS 💛 It's been 3+ weeks since your last visit and
  > {technician}'s schedule is almost full. Grabbed you $5 off if you book today: {link} -Lucy"
- **Transactional (not consented)** — same technician/timing framing, no discount language:
  > "Hi {name}! It's Lucy from AK.LUX.NAILS 💛 It's been 3+ weeks since your last visit and
  > {technician}'s schedule is almost full. Want me to grab you a spot? {link} -Lucy"
- **Technician-less fallback** (see D5 — used for both consent branches when `technician_name` is
  null/blank; the discount clause still only appears in the consented version):
  > Marketing: "Hi {name}! It's Lucy from AK.LUX.NAILS 💛 It's been 3+ weeks since your last visit.
  > Spots are filling up fast right now — grabbed you $5 off if you book today: {link} -Lucy"
  > Transactional: "Hi {name}! It's Lucy from AK.LUX.NAILS 💛 It's been 3+ weeks since your last
  > visit. Spots are filling up fast right now — want me to grab you a spot? {link} -Lucy"

**Style notes locked in alongside this copy** (see `[[sms_technician_gender]]` memory): "her" is used
for technicians (all current AK.LUX.NAILS technicians are women — confirmed by the owner, no longer
worked around with a gender-neutral "their" the way `same_day_rebooking_discount`'s copy does — see
Risks for whether that older copy should be aligned too), no em dash, full "It's Lucy from
AK.LUX.NAILS" greeting every time (this is a single, standalone touch 21–35 days out — no "3rd
message in a row today" concern the way `same_day_rebooking_discount` had, so the full greeting is
correct here, not an inconsistency).

**Deliberately not included: a "what could we do better" service-recovery question.** Discussed and
rejected for this specific text — this automation doesn't know *why* a customer didn't return (moved
away, one-time visitor, disliked something, or just forgot — most likely case), so a diagnostic
question risks presuming something went wrong for the majority who simply forgot. It would also
split the SMS across two asks (book vs. answer a question), diluting the one action that's actually
measured. The equivalent need is already served for free: **any reply to this text lands in
`/admin/messages`** the same as every other automation's replies, so a customer who *does* want to
explain something already has a frictionless, natural path — no explicit prompt required. A
systematic "why didn't you return" signal, if wanted later, should be its own separate mechanism
(e.g. a follow-up only for customers whose coupon expired unused), not layered onto this text.

**Why this is different from the first draft's `lead_follow_up`-style "no link, no incentive"
decision**: that draft avoided copying `same_day_rebooking_discount`'s shape because the data showed
it converting 0 of 9 sends, and the *mechanism* (not just the copy) was the suspect. A follow-up
diagnostic (2026-08-06, prompted directly by this design question) found:
- All 9 real sends show `delivery_status = delivered` in Twilio — the messages did reach real phones.
- The short-link route (`GET /r/{token}`) responds correctly (tested against a synthetic,
  non-real token — 404 as expected, not a crash or misroute).
- `REBOOKING_PROMO_SECRET` is present (non-empty) in **both** live backend containers and **both**
  live akluxnails-home containers — ruling out the specific "signing not configured → every link
  silently 404s" failure mode.
- All 9 real rows show `clicked_at IS NULL` — meaning nobody has reached `/r/{token}` **at all**,
  which is a fact established *before* any signature-verification logic even runs. This points away
  from a backend config bug and toward either (a) genuine low interest, or (b) the link/message
  being suppressed or flagged before the customer ever taps it (carrier or Apple Messages spam
  filtering is common for links sent from a young, low-volume, recently-verified toll-free number).

Given the mechanism itself checks out on every safely-testable dimension, and the owner explicitly
asked for this automation to use it, this design no longer treats "avoid the shared mechanism" as
the safe default. See Risks for what's still unconfirmed and how that risk is being carried forward,
not ignored.

### D9: New, separate $5 Square discount — its own group, its own promo code, reusing the shared signing/redirect infrastructure

**Decision**: a **new** Square customer group + pricing rule for $5 off (one-time Catalog setup,
external step — same shape as the still-pending $10 setup, tracked separately). Kept entirely
separate from the existing $10 group:
- New config: `RebookingProperties.winbackAutoDiscountGroupId` (mirrors `autoDiscountGroupId`,
  same `isXConfigured()` fail-open-to-no-op pattern).
- New promo code constant in `ShortLinkController`: `WINBACK_PREFIX = "WINBACK:"`,
  `WINBACK_PROMO_CODE = "WINBACK5"`, alongside the existing `REBOOK_PREFIX`/`REBOOK_PROMO_CODE`.
  `resolveRebookingPromo`'s body generalizes trivially — it already takes a promo code as a
  parameter internally, only the constant and the prefix-dispatch in `resolveTarget` are new.
- **No changes needed to `RebookingPromoSigner`** (Java) or `verifyRebookingPromoSignature`
  (TypeScript) — both already take the promo code as a parameter and sign/verify generically over
  it. This is the one piece of existing infrastructure that needed zero changes.
- akluxnails-home's promo-banner/booking-create code (`app/page.tsx`,
  `app/api/booking/create/route.ts`, `app/api/rebooking-promo/verify/route.ts`, all of which
  currently hardcode `REBOOK10`/$10) needs a second code→amount/group-id branch for `WINBACK5`/$5.
  **Confirmed with the owner: same $99 minimum-order gate as the $10 promo applies to `WINBACK5`
  too** — the same pricing-rule shape, just a different discount amount.
- The internal group-enroll endpoint (`InternalNotificationController`) needs to accept which group
  to enroll into (today it's implicitly the one `autoDiscountGroupId`) — smallest correct change is
  an optional `groupId` request parameter, defaulting to the existing $10 group for
  backward-compatible callers.

**Why a separate group instead of reusing the $10 one at a different rate**: Square pricing rules
are amount-specific, not parameterizable per-request — two different discount amounts need two
different rules/groups regardless. This also keeps the two automations' redemption data cleanly
separable in Square's own reporting.

### D10: Coupon expiry — end of the day it's sent, same timezone convention as `same_day_rebooking_discount`

**Decision**: reuse `SameDayRebookingTriggerService`'s exact pattern — `SALON_ZONE =
ZoneId.of("America/Los_Angeles")`, expiry = the start of the *next* calendar day in that zone:

```java
Instant promoExpiresAt = ZonedDateTime.now(SALON_ZONE).toLocalDate().plusDays(1)
        .atStartOfDay(SALON_ZONE).toInstant();
```

Computed once, inline, when the scheduler processes each eligible customer (see D4's
`promo_expires_at` column) — "end of day" here means the day the *SMS goes out* (10 AM send, expires
that midnight), not the day of the original visit, which was 21–35 days earlier and is otherwise
irrelevant to the coupon's own clock.

### D11: Ships disabled by default

**Decision**: `lapsed_customer_winback` seeds into `sms_automation` with `enabled = false`, the same
standing rule as every automation before it.

### D12: No new frontend pages required (salaryReview side)

**Decision**: `SmsAutomationRegistry` (code-level key → display name → audience description) is the
only place this needs to be described for `/owner/automations` to render it —
`SmsAutomationService.list()` already iterates `SmsAutomationRegistry.all()`. `/admin/messages`
groups `sms_message` rows by `phone_number` regardless of `automation_key`. (akluxnails-home *does*
need frontend changes — see D9 — but that's a promo-banner/booking-flow change on an existing
surface, not a new page.)

## Risks / Open Questions

- **The root cause of `same_day_rebooking_discount`'s 0-of-9 conversion is still unconfirmed.** The
  diagnostic in D8 ruled out the specific failure mode this design was originally worried about
  (broken signing config), but didn't identify what *is* actually suppressing clicks. If it turns
  out to be carrier/Apple Messages spam filtering on the toll-free number itself, that risk is
  inherited by this automation's marketing variant too, since it sends the same shape of link from
  the same number. **Recommendation**: watch this automation's own `clicked_at` rate closely from
  its very first sends rather than assuming the mechanism is now proven — and pursue the Twilio
  Console message-flagging check (see the parallel investigation) independently, not blocked on this
  change.
- **No built-in conversion-tracking dashboard.** Same acknowledged gap as the first draft — whoever
  implements this should expect to periodically run a manual query joining
  `lapsed_customer_winback_send WHERE state = 'SENT'` against a later `provider_visit` row for the
  same `square_customer_id`, the same way the profit report had to for `same_day_rebooking_discount`.
- **Whether to also update `same_day_rebooking_discount`'s existing copy to use "her"** instead of
  its current gender-neutral "the schedule" workaround, for style consistency across both
  automations — not required for this change, but flagged for a future touch of that copy.
- **10:00 AM send time is a starting guess**, not backed by data on this business's customers'
  actual SMS engagement-by-hour.
- **Overlap with `lead_follow_up` is structurally impossible, not just unlikely**: `lead_follow_up`
  only ever targets contacts with zero bookings; this automation only targets customers with exactly
  one completed visit. A given phone number can be eligible for at most one of the two at any time.
- **Overlap with `same_day_rebooking_discount` is also structurally impossible**: that automation
  only fires the same day as a checkout when the customer has *no* upcoming appointment yet; this one
  only fires 21–35 days after a visit. A customer could in principle receive both over time (checkout
  → no rebook → 21–35 days later, win-back fires) but never both *at once* for the same visit.
