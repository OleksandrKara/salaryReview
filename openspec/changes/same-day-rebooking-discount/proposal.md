## Why

Staff already run this play manually, in person: when a customer is happy with their visit, a
nail artist offers to rebook them for their next appointment on the spot, and if the customer
says yes, applies the existing Square catalog discount **"Same day rebooking discount"** ($10
fixed amount, catalog id `IK7PPLFVARHVUDTITUT5K4F2`) to their next visit. It works, but it only
reaches customers a staff member remembers to ask, in the room, at that exact moment.

The owner wants to extend the same offer automatically to every in-salon checkout via SMS — a
"clever" nudge to rebook same-day, with the same $10 discount, valid only until the end of the
day the customer paid (so it can't be hoarded or used weeks later, and creates real urgency).

This sits next to two other post-checkout automations that already exist —
`checkout_review_request` (2 min: review ask) and any future win-back sends — so the design
here is as much about **not over-texting a customer who just paid** as it is about the
discount mechanic itself. It also has to fit an SMS-compliance rule this codebase already
enforces: a message with a discount is MARKETING-class and requires
`marketing.contacts.sms_marketing_consent`, unlike the transactional automations shipped so
far.

**Two platform realities, confirmed directly via the real Square account before writing this
proposal, shape the design:**

1. **Square customers can carry SMS-marketing consent that lives in Square itself**, not just
   in this app's own `marketing.contacts` table — a real segment called `Text Subscribers`
   (`gv2:DN9J6H6X8D4NN9202T6PKWK43C`) exists in the account today, populated by Square's own
   opt-in flow, independent of the akluxnails-home SMS consent checkbox. The owner asked that
   consent be checked in **either** place.
2. **Square's Bookings API has no way to attach a real Order-level discount to an appointment
   before checkout** — Discounts apply to Orders, and an Order for a given appointment doesn't
   exist until a staff member checks it out at the register. However, re-checking Square's
   *Catalog* API turns up a different, real path to a genuinely automatic discount: a
   `CatalogPricingRule` scoped to a dedicated `CustomerGroup`, with a real, Square-enforced
   `minimumOrderSubtotalMoney` field. By adding a customer to that group only while their personal
   offer window is open (removed again once it expires), the $10 discount applies automatically
   the moment staff check the customer out — no manual tap required, and Square itself enforces
   the $99 minimum server-side. See design.md D7 for the full mechanism and its one real trade-off
   (it applies per matching Order for that customer, not narrowly to one specific appointment).

## What Changes

- **New automation: `same_day_rebooking_discount`.** Triggered off the same in-salon checkout
  signal `checkout_review_request` already uses (Square `payment.updated`, COMPLETED, not
  booking-linked). Fires 3 hours after checkout — after the 2-minute review-request text, with
  enough separation that a customer doesn't get two texts back to back — unless by then the
  customer already has an upcoming appointment (skip) or the day-end cutoff would have already
  passed (skip, never sends an expired offer).
- **Consent gate, checked in both places.** Sent only if the contact's
  `sms_marketing_consent = true` in `marketing.contacts`, **or** their linked Square customer
  record belongs to Square's own `Text Subscribers` segment. Either is sufficient.
- **A real, time-boxed, tamper-proof link.** The SMS links to
  `https://akluxnails.com/?promo=REBOOK10&exp=<epoch seconds>&sig=<HMAC signature>`, where `exp`
  is midnight `America/Los_Angeles` (PST/PDT, DST-safe) on the day the customer paid, and `sig`
  makes the expiry unforgeable by editing the URL — verified server-side twice (page render and
  booking creation), never trusted from the client. Wrapped in the existing click-tracked
  short-link redirect (`/r/{token}`) so we get click analytics for free.
- **A mobile-first promo banner on akluxnails.com**, shown only when a visitor arrives via that
  exact (signature-verified) link (not on a normal homepage visit), showing a live countdown to
  `exp` and an "offer expired" state once it passes.
- **A real $99 minimum, enforced by Square itself, not just this app's UI.** The discount only
  ever applies — automatically or in display — once the order subtotal reaches $99, using
  Square's own `CatalogPricingRule.minimumOrderSubtotalMoney` as the source of truth (see below),
  with the same threshold mirrored in this app's own price display.
- **Discount reflected in the booking flow's price display.** A visitor with a live (unexpired,
  signature-verified) promo and a $99+ cart sees the $10 discount subtracted from the displayed
  total before confirming — a **display-only** estimate, since (per an earlier, explicit owner
  decision) this booking flow never takes payment online; nothing is actually charged by this app
  either with or without the promo.
- **A real, automatically-applying discount at checkout — not just a staff note.** Completing a
  booking under a valid promo adds the customer to a dedicated Square customer group for the
  remainder of their personal offer window; a Square `CatalogPricingRule` scoped to that group
  automatically applies a $10 discount to any qualifying ($99+) Order Square generates for them —
  no staff action required. Membership is removed once their window expires. A `sellerNote` and a
  Telegram alert remain as a secondary safety net, now warning staff *not* to also apply the old
  manual discount (which would stack to $20 off) — see design.md D7 for the full mechanism and its
  one accepted trade-off.

## Non-goals

- No online payment is added anywhere in this flow — an explicit, earlier owner decision. The
  automatic discount is realized entirely through Square's own Pricing Rule / customer-group
  mechanism at whatever register/checkout flow staff already use; this app never touches money.
- No changes to the existing manual "Same day rebooking discount" catalog object or the staff
  habit of applying it in person for walk-in/verbal rebooking offers — a **separate** catalog
  discount object is created for the automatic path specifically to avoid disturbing that
  workflow (see design.md D7 on the double-discounting risk this separation still requires staff
  awareness of).
- No new email channel, no change to `checkout_review_request`'s own logic, no per-staff
  attribution.
- Not reusing `sms_reply_flow`/`SmsReplyFlowScheduler` — that state machine always transitions
  into an `AWAITING_REPLY` phase expecting a customer text back, which doesn't apply here (the
  "reply" is a website visit + booking, not an SMS reply). A parallel, simpler table follows the
  same precedent `lead_followup_send` set for one-way sends.

## Capabilities

### New Capabilities

- `same-day-rebooking-discount-automation` (salaryReview): trigger, consent gate (dual-source),
  expiry-aware delayed send, HMAC-signed and click-tracked promo link, per-customer Square
  customer-group expiry sweep.
- `same-day-rebooking-promo-landing` (akluxnails-home): promo-aware homepage banner with live
  countdown, $99-gated discount reflected in booking-flow price display, automatic Square
  discount via customer-group membership plus a `sellerNote` + Telegram alert on a promo-flagged
  booking.

### Modified Capabilities

*(none — `checkout_review_request`'s own trigger/logic is unchanged; this change only adds a
second, independent enqueue off the same webhook event.)*

## Impact

- **Backend (salaryReview)**: new migrations (`same_day_rebooking_send` table,
  `same_day_rebooking_group_membership` table, seeds the automation disabled), new
  `SmsTemplateRegistry` entry (MARKETING class), a small addition to
  `CheckoutReviewTriggerService`'s webhook handling (or a sibling service called from the same
  spot) to also enqueue this automation, a new send scheduler, a new group-membership-expiry
  scheduler, an extension to `SquareClient.Customer` to expose `segmentIds` for the Square-side
  consent check, a new `REBOOKING_PROMO_SECRET` config value for HMAC-signing the promo link, and
  a small extension to `ShortLinkController`'s link-target resolution to support a dynamic,
  signed, expiry-bearing promo target alongside the two existing fixed ones.
- **One-time Square Catalog/account setup** (not app code — see design.md D7/Risks, requires
  explicit owner confirmation of the exact objects before creation): a new automatic-application
  `CatalogDiscount`, a new `CustomerGroup`, an all-products `CatalogProductSet`, and a
  `CatalogPricingRule` tying them together with a $99 minimum-order-subtotal.
- **Frontend (akluxnails-home)**: promo query-param + signature verification on the homepage
  (server-side), a new banner component with a live countdown, promo state threaded through
  `useBookingFlow`, a $99-gated display-only discount line in the booking summary, and a
  customer-group-membership + `sellerNote` + Telegram-alert addition to booking creation (with
  independent signature re-verification in the API route, not just trusting the page state).
