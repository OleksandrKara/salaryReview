## Context

Builds on `sms-automation-platform` (registry, compliance-class gate, `TwilioSmsService`),
`sms-automations-hub` (activity log, `sms_message`, the click-tracked `/r/{token}` short link),
and reuses the exact webhook signal `checkout_review_request` already established
(`CheckoutReviewTriggerService.handlePaymentUpdated`, Square `payment.updated`, COMPLETED, not
booking-linked, phone resolved from the order's customer). This change adds a second, independent
send off that same signal, a new consent-check path that also reads Square's own customer
segments, and a promo-aware landing/booking experience on akluxnails-home.

## Goals / Non-Goals

**Goals**: (1) nudge a just-serviced customer to rebook same-day with a real, time-boxed $10
discount; (2) never stack this text awkwardly close to the existing 2-minute review-request text;
(3) honor SMS-marketing consent recorded in *either* this app or Square itself; (4) make the offer
impossible for staff to miss at checkout, within what Square's Bookings API actually allows; (5)
keep the booking flow payment-free, per the owner's explicit simplification.

**Non-goals**: online payment of any kind; a literal Square Order-level discount attached before
checkout (not supported by the platform); reusing `sms_reply_flow`'s reply-wait state machine;
changing `checkout_review_request` itself; per-staff attribution.

## Decisions

### D1: Second, independent enqueue off the same webhook signal, own table, own scheduler

**Decision**: `CheckoutReviewTriggerService.handlePaymentUpdated` already does all the qualifying
work this automation also needs (COMPLETED, not booking-linked, resolves a customer + phone) —
after it enqueues its own `checkout_review_request` flow, it also calls a new
`SameDayRebookingTriggerService.enqueue(payment, order, customerId, phoneNumber, customerName)`
with the already-resolved values (no duplicate Square lookups). That service writes one row to a
new table:

```sql
CREATE TABLE same_day_rebooking_send (
    id                 BIGSERIAL   PRIMARY KEY,
    phone_number       TEXT        NOT NULL,
    customer_name      TEXT,
    square_customer_id TEXT        NOT NULL,
    square_payment_id  TEXT        NOT NULL,
    send_due_at        TIMESTAMPTZ NOT NULL,
    promo_expires_at   TIMESTAMPTZ NOT NULL,
    state              TEXT        NOT NULL CHECK (state IN
        ('AWAITING_SEND', 'SENT', 'SKIPPED_BOOKED', 'SKIPPED_NO_CONSENT',
         'SKIPPED_EXPIRED', 'SKIPPED_DISABLED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX same_day_rebooking_send_due_idx ON same_day_rebooking_send (state, send_due_at);
CREATE UNIQUE INDEX same_day_rebooking_send_payment_idx ON same_day_rebooking_send (square_payment_id);
```

**Why not reuse `sms_reply_flow`**: that table's scheduler (`SmsReplyFlowScheduler`) always moves
a sent message into `AWAITING_REPLY` and starts a 24h reply-expiry countdown — semantics specific
to `checkout_review_request`'s "wait for YES/STOP" branch logic. This automation's "reply" is a
website visit, not a text back; forcing it through that state machine would mean branching
`SmsReplyFlowScheduler` by `automation_key` and growing an already-focused class. A second small
table, one-way like `lead_followup_send`, keeps both schedulers simple.

**Why not reuse `lead_followup_send` either**: different trigger (webhook vs. poll-on-contacts),
different columns (`promo_expires_at`, `square_payment_id` idempotency key have no equivalent
there). Same *shape* of decision as `lead_followup_send`'s own D3, applied again here.

### D2: 3-hour delay, and the expiry is anchored to payment time, not send time

**Decision**: `send_due_at = payment completed time + 3 hours` (the earlier-agreed delay,
comfortably clear of the 2-minute review text). `promo_expires_at` is computed **once, at enqueue
time**, as midnight `America/Los_Angeles` on the calendar day the payment completed (DST-safe —
`ZoneId.of("America/Los_Angeles")`, not a fixed UTC offset) — not recomputed at send time, since
the offer is "valid through the day you paid," a fact fixed at the moment of payment.

**Edge case this creates, handled explicitly**: a checkout very late in the day (e.g. 10:30pm)
means the 3-hour send would fire at 1:30am, *after* `promo_expires_at` has already passed. The
scheduler checks `promo_expires_at` before sending: if it's already in the past, the row is
written `SKIPPED_EXPIRED` and nothing is sent — sending a link to an already-dead offer would be
actively confusing, not just unhelpful. In practice this only affects checkouts roughly within 3
hours of midnight PST.

### D3: Consent gate checks marketing.contacts OR Square's own "Text Subscribers" segment

**Decision**: this message contains a real, expiring discount — squarely MARKETING under the
standing compliance rule (`SmsMessageClass.MARKETING`), unlike every transactional automation
shipped so far. Sent only if:

```
marketing.contacts.sms_marketing_consent = true
  for a contact resolved by phone number,
OR
the Square customer (square_customer_id, already resolved from the triggering order)
  has segment_ids containing "gv2:DN9J6H6X8D4NN9202T6PKWK43C" ("Text Subscribers")
```

Confirmed live against the production Square account: this segment is real, currently populated,
and distinct from `marketing.contacts.sms_marketing_consent` (customers can land in it via
Square's own checkout/marketing opt-in flow, independent of the akluxnails-home consent
checkbox) — exactly the "consent in either platform" the owner asked for.

**Implementation note**: `SquareClient.Customer` currently exposes
`(id, givenName, familyName, createdAt, phoneNumber)` — this change adds `segmentIds` (a
`List<String>`, straight passthrough from Square's customer object) so this check can be a plain
`contains()` call, no new Square endpoint needed (customers are already being fetched for phone
resolution in the trigger path). The specific segment id is a value tied to this Square account,
not a stable platform constant — it lives in application config
(`square.rebooking-consent-segment-id`), not hardcoded in Java, so it survives if the segment is
ever recreated with a new id.

**No consent found in either place** → row written `SKIPPED_NO_CONSENT`, no send.

### D4: Upcoming-appointment check reuses the exact `SquareBookingFilters` pattern from `lead_follow_up`

**Decision**: at send time (not enqueue time — appointments can change in the 3-hour gap), check
whether `square_customer_id` (already known, no phone-number re-resolution needed here, unlike
`lead_follow_up` which sometimes has no Square link yet) has any booking where
`SquareBookingFilters.didHappen(booking)` and `startAt` is still in the future. If so, they've
already rebooked (maybe the in-person offer already worked) — `SKIPPED_BOOKED`, no send.

### D5: Message copy and the promo link

**Decision** (copy to be finalized with the owner, shape fixed here): *"Hi {name}! So glad you
loved your visit today 💅 Rebook before midnight and take $10 off your next appointment (min. $99
service total) — grab your spot: {link} — AK.LUX.NAILS"* (name-less fallback drops the greeting
name only). The $20 free-nail-design-for-5-star-review incentive from the separate review-request
automation is **not** mentioned here — the owner was undecided on highlighting it in that other
message and this is a different automation entirely; not folding one incentive's copy into
another's.

**Link**: `https://akluxnails.com/?promo=REBOOK10&exp=<epoch seconds>&sig=<signature>` — see D8 for
why `sig` exists and how it's computed. Wrapped through the existing click-tracked redirect
(`GET /r/{token}` → `sms_message.link_target`/`clicked_at`, see `sms-automations-hub` design.md
D6). Today `ShortLinkController`/`CheckoutReviewLinks.resolve()` only maps two fixed string
targets to fixed URLs; this change adds a third recognized shape — a `link_target` value of the
form `REBOOK:<epochSeconds>` — resolved by building the signed URL above instead of a static
lookup (the signature is deterministic from the epoch + a shared secret, so nothing extra needs
to be stored to reconstruct it at resolve time). This keeps click-through analytics (did they even
tap the link?) for free, on top of the promo's own banner/booking tracking on the akluxnails-home
side.

### D6: Promo banner — mobile-first, only on a tracked visit, live countdown

**Decision**: `app/page.tsx` reads `?promo=` and `?exp=` search params (same existing pattern
already used for landing-page variant selection, see `lead-followup-and-manager-inbox` notes on
`app/page.tsx` lines 21-31). When both are present:
- `exp` in the future → a sticky, mobile-first banner (full-width on small screens, not a corner
  toast easy to miss or a modal that blocks browsing) showing the discount and a live countdown
  (`HH:MM:SS` to midnight, ticking client-side, no server round-trip needed since `exp` is just a
  fixed epoch already in the URL).
- `exp` in the past → the same banner slot instead shows a plain "this offer has expired" state,
  so a customer who dawdles or reopens an old text link gets an honest answer instead of a banner
  that silently vanishes.
- No `promo`/`exp` params at all (a normal, untracked homepage visit) → no banner, no change from
  today. This is the "only for a specific link" behavior confirmed with the owner.

The promo state (`promo` code + `exp` epoch + `sig` + expired boolean, all verified server-side —
see D8) is threaded into `useBookingFlow`'s state once, on mount, exactly like the existing
tech-selection/slot state — not re-read from the URL at every step, so it survives whatever step
the customer is on.

### D7: A real, automatically-applying discount — revised after confirming Square's Pricing Rules

**Superseded decision**: an earlier draft of this design concluded that Square's Bookings API has
no way to attach a real discount ahead of checkout, and proposed a `sellerNote` + Telegram alert
as the ceiling of what's achievable. That conclusion about the *Bookings* API is still correct —
but re-checking Square's *Catalog* API (`CatalogPricingRule`, prompted by the owner explicitly
asking to double-check) turns up a mechanism that gets to a genuinely automatic discount by a
different route entirely: **automatic, customer-group-scoped pricing rules**, confirmed directly
against the real Square SDK types (`CatalogPricingRule.d.ts`, `CatalogDiscount`,
`CatalogProductSet.d.ts`, and the `customers.groups` client for group membership).

**How it works**:
1. **One-time setup** (Catalog objects, created once, reused by every send — not per-customer):
   - A new `CatalogDiscount`, separate from the existing manually-applied "Same day rebooking
     discount" ($10, `IK7PPLFVARHVUDTITUT5K4F2`) so the staff-facing manual workflow is completely
     untouched — call it **"Same-Day Rebooking Auto-Discount"**, `FIXED_AMOUNT` $10,
     `application_method: AUTOMATICALLY_APPLIED`.
   - A new `CustomerGroup`, **"Same-Day Rebooking — Active"** — membership in this group is what
     turns the discount on for a specific customer, and is added/removed programmatically (below).
   - A `CatalogProductSet` with `allProducts: true` — the discount can apply against any service,
     since eligibility is about the $99 subtotal minimum, not specific items.
   - A `CatalogPricingRule` tying these together: `discountId` = the auto-discount above,
     `matchProductsId` = the all-products set, `customerGroupIdsAny` = [the group's id],
     **`minimumOrderSubtotalMoney` = $99.00** — this is Square's own, server-enforced minimum
     (`CatalogPricingRule.minimumOrderSubtotalMoney` is a real, documented field), directly
     implementing the owner's $99 floor at the platform level, not just in this app's own display
     math. No `validFromDate`/`validUntilDate` is set on the rule itself — expiry is handled per
     customer via group membership (below), since each customer's own cutoff is a different
     moment, not one shared calendar date.
2. **Per booking**: when `app/api/booking/create/route.ts` creates a booking under a valid,
   unexpired, signature-verified promo, it calls `client.customers.groups.add({ customerId,
   groupId })` — Square's real, documented "add a customer to a group" endpoint. From that instant,
   *any* Order Square generates for that customer (in particular, the Order its own Appointments
   checkout flow creates when staff processes this appointment) automatically gets the $10 off,
   with zero staff action, as long as the order subtotal is at least $99.
3. **Per-customer expiry**: a new small salaryReview-owned table,
   `same_day_rebooking_group_membership (square_customer_id, expires_at, removed_at)`, written by
   the same booking-create call (via an internal endpoint, mirroring the existing
   Telegram/SMS-relay pattern already used for cross-app calls) and polled by a new scheduler that
   calls `client.customers.groups.remove({ customerId, groupId })` once `expires_at` passes —
   turning the discount back off for that customer without touching the shared Catalog objects at
   all.
4. **Staff visibility, kept as a second, redundant safety net**: `booking.sellerNote` is still set
   (as the earlier draft proposed), but now reads e.g. *"🎁 Auto-discount active for this customer
   until [date] (min. $99 order) — do NOT also apply the manual 'Same day rebooking discount' or
   they'll get $20 off, not $10."* This exists specifically to prevent **double-discounting**: a
   staff member unaware the automatic rule exists might otherwise apply the old manual discount
   out of habit, stacking both. The Telegram alert carries the same warning.

**Price display (unchanged from the earlier draft)**: the booking summary still shows a $10
deduction line client-side, using the same display-only estimate math
(`computeBookingPriceCents()`), gated on the running subtotal being at least $99 — if the
customer's current cart is under $99, no discount line is shown (matches what Square will actually
do once the Order exists), though the promo/countdown banner itself still shows (it can't know the
cart total before services are even picked).

**Accepted trade-off, called out explicitly**: because the Pricing Rule keys off *customer group
membership* + *order subtotal*, not "this specific appointment," the $10 auto-discount would also
apply to **any other qualifying Order** Square creates for that same customer while they're in the
group (e.g., a same-day retail purchase unrelated to the appointment, or if the group-removal
scheduler is delayed). Given the group window is short (hours, until that customer's own midnight
cutoff) and membership is only granted right when they complete the actual promo booking, this is
judged an acceptable, low-probability edge case rather than a reason not to build this — but it is
a real behavior difference from "only this one appointment gets the discount," and needs explicit
owner sign-off before enabling, same as the copy does.

### D8: The promo link is HMAC-signed — the `exp` value cannot be forged by editing the URL

**Decision**: `exp` (and the promo code) are covered by a signature so a customer can't simply
extend their own discount window by editing the URL. salaryReview computes
`sig = base64url(HMAC-SHA256(REBOOKING_PROMO_SECRET, "REBOOK10." + expEpochSeconds))` when
resolving the `/r/{token}` redirect (deterministic from `promo_expires_at` and a shared secret —
nothing extra needs to be persisted to reconstruct it), producing
`https://akluxnails.com/?promo=REBOOK10&exp=<epoch>&sig=<sig>`.

`REBOOKING_PROMO_SECRET` is a new shared secret (generated once, stored in both apps'
`.env`/compose environment — same pattern as `INTERNAL_API_KEY`, but a distinct secret, since this
one signs a public, customer-facing link rather than authenticating server-to-server calls, and
the two shouldn't be conflated).

**Verification happens twice, both server-side, never trusting the client**:
1. `app/page.tsx` (a server component — same convention already used for reading `searchParams` for
   landing-page variants) recomputes the HMAC over the `promo`/`exp` values it received and
   compares to `sig` using a constant-time comparison. Only if it matches does it pass a verified
   `{ code, expiresAt }` down to the client banner/booking state; on any mismatch (tampered,
   truncated, or missing `sig`) it behaves exactly as if no promo were present at all — fails
   closed, same philosophy as `LeadFollowUpScheduler`'s Square-lookup-failure handling elsewhere in
   this codebase.
2. `app/api/booking/create/route.ts` independently re-verifies the same signature before deciding
   to add the customer to the auto-discount group or set the `sellerNote` — since a client could in
   principle call this API directly with a hand-crafted request, bypassing the UI/page entirely.
   The signature check here, not "the banner was showing," is what actually gates the discount.

The secret never reaches client-side JavaScript — both checks happen in server components / API
route handlers.

### D9: `sms_message.link_target` gets a second recognized shape, not a schema change

**Decision**: no migration needed for the short-link change — `link_target` is already a free
`TEXT` column (no CHECK constraint, no FK), so `REBOOK:<epochSeconds>` is just a new string shape
`CheckoutReviewLinks.resolve()`/its replacement needs to recognize, alongside the two existing
fixed values, building the signed URL from D8 rather than a static lookup. Kept as a small,
explicit prefix check rather than a JSON blob, matching this codebase's existing "plain code, not
owner-editable copy" convention for link targets.

### D10: New automation ships disabled by default

**Decision**: `same_day_rebooking_discount` seeds into `sms_automation` with `enabled = false`,
same standing rule every automation before it has followed. The owner turns it on from
`/owner/settings/sms` once satisfied with the copy, the live banner/booking experience, **and**
the one-time Square Catalog/CustomerGroup setup from D7 is confirmed in place.

## Risks / Open Questions

- **Copy needs final owner sign-off** — the shape in D5 is a starting point, not a locked string,
  same as every other automation's template has been refined in review before enabling.
- **D7's one-time Square Catalog setup (Discount + CustomerGroup + ProductSet + PricingRule) is a
  real, persistent, account-wide configuration change** — it must be created deliberately, with
  explicit owner confirmation of the exact objects before any are created against the live
  production Square account, same standing rule as every other live external-service change made
  this session. This is not something to create silently while implementing the rest of the
  feature.
- **Double-discounting risk (D7)**: a staff member unaware of the new automatic rule could still
  manually apply the old "Same day rebooking discount" on top of the now-automatic one, giving a
  customer $20 off instead of $10. Mitigated via the `sellerNote`/Telegram warning, but this is a
  process risk, not something code alone fully prevents — worth a short heads-up to staff when
  this ships.
- **Pricing rule applies per matching Order, not per specific appointment (D7)**: see the accepted
  trade-off above — any other same-day Order for a grouped customer above $99 also gets the $10
  off while they're a member.
- **Segment id fragility**: `gv2:DN9J6H6X8D4NN9202T6PKWK43C` (consent, D3) is specific to this
  Square account and could theoretically change if the segment is ever deleted/recreated — kept in
  config, not code. The new Catalog objects from D7 (discount/group/pricing-rule ids) have the same
  property and are likewise kept in config once created, not hardcoded.
- **Whole-ticket, not per-service, discount**: the $10 is a single deduction against the order
  subtotal (both in Square's own Pricing Rule and in this app's display math), not a markdown
  applied to each selected service individually — confirmed this matches what the owner meant by
  "price for services will show the discount" (a single line against the total, gated at $99
  minimum), not a per-service split.
