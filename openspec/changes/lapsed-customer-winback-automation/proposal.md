## Why

A production-data profit-optimization review (see `docs/` conversation, 2026-08-06 — direct query
against `provider_visit`, 2,274 rows, service dates 2025-07-01 through 2026-08-05) found the single
largest addressable pool in the entire customer base has no automation pointed at it at all:

- **906 distinct customers all-time; 542 (59.8%) have exactly one visit and never returned.** 364
  (40.2%) have come back at least once.
- **Median gap between a repeat customer's visits is 28 days** (mean 32.7, IQR 21–35) — a clean,
  well-defined, data-backed window, not a guess.
- Every SMS automation shipped so far targets a different moment in the lifecycle:
  `checkout_review_request` and `same_day_rebooking_discount` both fire the day of checkout;
  `lead_follow_up` targets a lead who never booked at all. **None of the four live automations are
  scoped to "a real, paying customer whose natural rebooking window has quietly passed."**

This is the #1-ranked opportunity in that review, ahead of every marketing-spend lever, specifically
because the pool (542+ and refilling every month as new first-timers arrive) is larger than anything
else identified.

**Update (2026-08-06, after further discussion with the owner):** the original draft of this
proposal deliberately avoided any discount, citing `same_day_rebooking_discount`'s 0-of-9-sends
conversion as a reason not to copy its incentive-plus-link shape. A follow-up diagnostic (see
design.md's Risks section) found that automation's SMS *delivery* is confirmed working (Twilio
reports `delivered` on all 9 sends) and its signing secret is present and non-empty on both sides
(salaryReview and akluxnails-home) — ruling out the specific "link silently 404s" failure mode this
proposal originally worried about copying. The root cause of the 0% conversion is still unconfirmed
(most likely carrier/Apple Messages spam filtering on a young toll-free number, or genuine low
appeal — see design.md), but the *mechanism itself* (signed promo link, discount applied on click,
independent of what the SMS text says) is no longer suspected of being broken. On that basis, the
owner asked for this automation to use the same proven mechanism instead of avoiding it.

## What Changes

- **New automation: `lapsed_customer_winback`.** Once a day, find customers with exactly one
  all-time visit in `provider_visit` whose `service_date` is 21–35 days in the past, who have no
  upcoming Square appointment, and who have never left negative feedback. Send **one of two message
  variants** depending on SMS-marketing consent, both carrying the same click-tracked coupon link:
  - **Marketing (consented)**: explicitly mentions a **$5 coupon**, valid until end of the day it's
    sent.
  - **Transactional (not consented)**: no discount language at all — just a warm, plain link back to
    book. The coupon still silently applies if they click and book before it expires (same
    click-applies-regardless-of-wording mechanism `same_day_rebooking_discount` already uses), but
    the SMS itself makes no promotional claim, keeping it compliant as transactional.
  - Both variants ship **disabled by default**, same rule as every automation before it.
- **New tracking table `lapsed_customer_winback_send`**, one row per customer, ever — this is a
  single "we miss you" nudge per person, not a recurring campaign against the same customer.
- **New, separate $5 Square discount group + pricing rule** (one-time Catalog setup, external —
  mirrors the still-pending $10 setup tracked for `same_day_rebooking_discount`). Kept entirely
  separate from the existing $10 group so the two automations' discounts never collide or double up
  for a customer eligible for both (structurally they can't be — see design.md).
- **Small extensions to already-shipped infrastructure**, not new infrastructure: `ShortLinkController`
  gains a second promo-code branch (`WINBACK5` alongside the existing `REBOOK10`), akluxnails-home's
  promo-banner/booking-create code gains a second code→amount/group mapping. The HMAC
  verification itself (`RebookingPromoSigner` / `verifyRebookingPromoSignature`) is already generic
  over the promo code and needs no changes.
- No new frontend pages required: `/owner/automations` and `/admin/messages` remain fully
  registry/log-driven (see design.md D10).

## Non-goals

- **Not a fix to `same_day_rebooking_discount`'s conversion problem.** That investigation continues
  in parallel (see design.md Risks) — this change reuses its *mechanism*, which is now believed
  sound, without waiting for its *root cause* (why real customers aren't clicking) to be fully
  resolved. If that turns out to be a systemic deliverability problem (e.g. carrier filtering on the
  toll-free number), it would affect this automation too — see the explicit risk callout in design.md.
- **Not a general lifecycle/segmentation engine.** This targets exactly one segment (one-visit
  customers, 21–35 days out) with one coupon. Extending to "any repeat customer overdue past their
  own personal median gap" (mentioned as a longer-term idea in the same report) is future work, not
  this change.
- **Not a new conversion-attribution dashboard.** No new UI is proposed for measuring this
  automation's return beyond what already exists (`clicked_at` on the tracked link, the same manual
  join-against-`provider_visit` check recommended for `same_day_rebooking_discount`) — see design.md's
  Risks section.
- **Not a change to the existing $10 same-day-rebooking discount** — separate group, separate promo
  code, separate config, on purpose (see design.md).

## Verification

Backend unit tests on `LapsedCustomerWinbackScheduler`'s eligibility/skip/consent branches, the
boundary-day edges of the 21–35 day window, and the coupon-expiry computation (see tasks.md §3), plus
a read-only spot-check of the eligibility query directly against production before the automation is
ever enabled (tasks.md §4.3). No Square *writes* happen at send time (the discount group enrollment
only happens later, on the customer's own click, via the already-shipped group-enroll endpoint) —
consistent with this codebase's standing Square-is-read-only-from-the-scheduler rule. Both variants
ship **disabled** (D9); actually turning them on is an explicit, separate owner decision made after
reviewing final copy and after the $5 Square Catalog group/pricing rule is set up.
