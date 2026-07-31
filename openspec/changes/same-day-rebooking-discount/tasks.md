## 0. One-time Square account setup (not app code — do NOT execute silently)

- [ ] 0.1 **Requires explicit owner confirmation of exact object names/values before creation** —
      this creates real, persistent, account-wide Square Catalog objects (see design.md D7,
      Risks): a new `CatalogDiscount` ("Same-Day Rebooking Auto-Discount", $10 FIXED_AMOUNT,
      `application_method: AUTOMATICALLY_APPLIED` — kept separate from the existing manual "Same
      day rebooking discount" `IK7PPLFVARHVUDTITUT5K4F2`); a new `CustomerGroup` ("Same-Day
      Rebooking — Active"); a new `CatalogProductSet` (`allProducts: true`); a new
      `CatalogPricingRule` linking them with `minimumOrderSubtotalMoney = $99.00`
- [ ] 0.2 Record the resulting discount/group/pricing-rule ids in salaryReview config (not
      hardcoded), same treatment as the existing `square.rebooking-consent-segment-id`
- [ ] 0.3 Generate `REBOOKING_PROMO_SECRET` (random, high-entropy) and add it to both apps'
      `.env`/compose environment (see design.md D8) — never logged, never sent to the client

## 1. Backend (salaryReview) — schema

- [ ] 1.1 Create `V55__same_day_rebooking_send.sql`: `same_day_rebooking_send` table (see
      design.md D1) with a unique index on `square_payment_id` and a `(state, send_due_at)` index;
      also `same_day_rebooking_group_membership (square_customer_id, expires_at, removed_at)` for
      the group-expiry sweep (see design.md D7)
- [ ] 1.2 Same migration: seed `sms_automation` with `same_day_rebooking_discount`
      (`enabled = false` — see design.md D10)
- [ ] 1.3 Add config properties: `square.rebooking-consent-segment-id` (defaults to the current
      `gv2:DN9J6H6X8D4NN9202T6PKWK43C` — design.md D3), `square.rebooking-auto-discount-group-id`,
      `square.rebooking-auto-discount-id` (from task 0.2), `rebooking.promo-secret` (from task 0.3)

## 2. Backend (salaryReview) — trigger + consent + send + signing

- [ ] 2.1 Extend `SquareClient.Customer` with `segmentIds` (`List<String>`, passthrough from
      Square's customer object)
- [ ] 2.2 New `SameDayRebookingTriggerService.enqueue(...)`, called from
      `CheckoutReviewTriggerService.handlePaymentUpdated` right after its existing
      `checkout_review_request` enqueue, reusing the already-resolved payment/order/customer/phone
      (no duplicate Square calls) — computes `send_due_at` (+3h) and `promo_expires_at` (midnight
      `America/Los_Angeles` on payment day) per design.md D2
- [ ] 2.3 New `SameDayRebookingSendRepository` (mirrors `LeadFollowUpSendRepository`'s shape,
      plus `existsBySquarePaymentId`)
- [ ] 2.4 New `SmsTemplateRegistry` entry `same_day_rebooking_nudge` (MARKETING class,
      name-less-greeting fallback, mentions the $99 minimum — see design.md D5 for starting copy)
- [ ] 2.5 New `SameDayRebookingScheduler` (`@Scheduled(fixedDelay=15_000)`): for each row past
      `send_due_at` still `AWAITING_SEND` — check `promo_expires_at` (past → `SKIPPED_EXPIRED`);
      check upcoming appointment via `SquareClient.bookingsForCustomer` + `SquareBookingFilters`
      (found → `SKIPPED_BOOKED`); check `SmsAutomationService.isEnabled(...)` (disabled →
      `SKIPPED_DISABLED`); check consent via `marketing.contacts.sms_marketing_consent` OR
      `segmentIds.contains(configured segment id)` (neither → `SKIPPED_NO_CONSENT`); otherwise
      send via `TwilioSmsService.sendTemplated`, write `SENT`
- [ ] 2.6 New `RebookingPromoSigner` utility: `sign(promoCode, expEpochSeconds)` →
      base64url HMAC-SHA256 using `rebooking.promo-secret` (see design.md D8) — shared shape that
      both the short-link resolver (below) and akluxnails-home's verification must agree on
      byte-for-byte
- [ ] 2.7 Extend the short-link target resolution (`CheckoutReviewLinks`/`ShortLinkController`) to
      recognize a `REBOOK:<epochSeconds>` `link_target` shape, resolving to
      `https://akluxnails.com/?promo=REBOOK10&exp=<epochSeconds>&sig=<RebookingPromoSigner.sign(...)>`
      (see design.md D5/D8/D9) — set this `link_target` when the `sms_message` row for this send is
      logged
- [ ] 2.8 New `SameDayRebookingGroupExpiryScheduler` (`@Scheduled(fixedDelay=...)`): for each
      `same_day_rebooking_group_membership` row past `expires_at` with `removed_at IS NULL`, call
      the internal endpoint (task 3.2) or `SquareClient` directly to remove that customer from the
      auto-discount group, then set `removed_at`

## 3. Backend (salaryReview) — internal endpoint for akluxnails-home to call

- [ ] 3.1 New internal endpoint (same `X-Internal-Api-Key` pattern as the existing Telegram/SMS
      relay) that akluxnails-home's booking-create route calls after creating a promo-flagged
      booking: verifies the signature server-side again (never trusts that the caller already
      checked), adds the resolved Square customer to the auto-discount group via
      `client.customers.groups.add(...)`, and writes a `same_day_rebooking_group_membership` row
      with that customer's `promo_expires_at`
- [ ] 3.2 The same endpoint (or a sibling) exposes customer-group removal for task 2.8, unless
      salaryReview calls Square directly for removal (either is fine — pick whichever keeps Square
      credentials in one place, consistent with this codebase's existing ownership convention)

## 4. Tests (salaryReview)

- [ ] 4.1 `SameDayRebookingScheduler`: covers every state transition in isolation — sent path;
      `SKIPPED_EXPIRED` (promo_expires_at in the past at send time); `SKIPPED_BOOKED`;
      `SKIPPED_DISABLED`; `SKIPPED_NO_CONSENT` (neither source consents); consent-present-only-in-
      Square and consent-present-only-in-salaryReview both succeed
- [ ] 4.2 `same_day_rebooking_discount` seeds disabled immediately after `V55` runs
- [ ] 4.3 `RebookingPromoSigner`: deterministic for the same inputs, differs for any changed input
      (code, epoch, or secret) — this is the property the tamper-resistance in design.md D8 relies
      on
- [ ] 4.4 Short-link resolution: a `REBOOK:<epochSeconds>` target resolves to the correctly signed
      promo URL; existing `GOOGLE_REVIEW`/`FEEDBACK_FORM` targets are unaffected
- [ ] 4.5 `SameDayRebookingTriggerService`: enqueues exactly once per `square_payment_id`
      (idempotent against Square's redelivery, same as `checkout_review_request`)
- [ ] 4.6 Internal group-enroll endpoint: rejects a request whose signature doesn't verify; on a
      valid signature, calls the Square group-add and writes the membership row with the correct
      `expires_at`
- [ ] 4.7 `SameDayRebookingGroupExpiryScheduler`: a membership row past `expires_at` triggers
      removal and sets `removed_at`; a row not yet expired is left alone

## 5. Frontend (akluxnails-home) — promo banner

- [ ] 5.1 `app/page.tsx` (server component): read `promo`/`exp`/`sig` search params, recompute the
      expected signature server-side using the same secret/algorithm as `RebookingPromoSigner`, and
      only pass a verified `{ code, expiresAt }` down when it matches (design.md D8) — mismatched
      or missing signature behaves as if no promo params were present at all
- [ ] 5.2 New promo banner component: mobile-first full-width layout, live countdown while
      verified `exp` is future, "expired" state once past, renders nothing when unverified/absent
      (design.md D6)

## 6. Frontend (akluxnails-home) — booking flow

- [ ] 6.1 Thread verified promo state (`code`, `expiresAt`, `expired`) into `useBookingFlow` on
      mount, alongside existing state
- [ ] 6.2 Booking summary (Confirm/Details step): show a $10 deduction line only when an unexpired,
      verified promo is active **and** the running subtotal is at least $99, computed the same
      display-only way `computeBookingPriceCents()` already works (design.md D7) — no change to
      any real payment path (none exists)
- [ ] 6.3 `app/api/booking/create/route.ts`: independently re-verify the promo signature (never
      trust client-forwarded promo state alone — design.md D8); if valid and unexpired, pass a
      `sellerNote` (naming the automatic discount, the cutoff, and warning staff not to also apply
      the manual "Same day rebooking discount") into `createBooking()`, and call the new internal
      endpoint (task 3.1) to enroll the customer in the auto-discount group
- [ ] 6.4 `lib/square/bookings.ts`: extend `CreateBookingInput`/`createBooking()` to accept and
      pass through `sellerNote`
- [ ] 6.5 `lib/telegram.ts`: new alert variant for a promo-flagged booking (customer, appointment
      time, the same double-discount warning as the seller note), fired alongside existing
      booking-confirmation notification when applicable

## 7. Verification

- [ ] 7.1 `mvn test` clean; `tsc`/`eslint`/`next build` clean on akluxnails-home
- [ ] 7.2 Real E2E check (read-only where possible, throwaway test data cleaned up per standing
      rule): confirm a synthetic qualifying payment enqueues both `checkout_review_request` and
      `same_day_rebooking_send` rows; confirm the scheduler correctly skips a `SKIPPED_EXPIRED`
      case (checkout very late in the day) without sending; confirm the promo banner renders only
      with a valid signature, on a real mobile viewport, and that editing `exp` in the URL breaks
      verification; confirm the $99 gate holds both client-side and (once task 0.1 is live) at
      Square's own pricing-rule level; confirm a completed booking under an active promo enrolls
      the customer in the auto-discount group (then clean up: remove the membership and any test
      booking) and a Telegram alert fires
- [ ] 7.3 Owner review and explicit sign-off on: final message copy (D5), the exact Catalog objects
      before task 0.1 is executed, and the accepted per-customer-not-per-appointment trade-off
      (D7) — do not enable the automation in production before this
- [ ] 7.4 Push both repos to new branches, open PRs, wait for CI, ask for explicit merge/deploy
      confirmation — same as every prior change
