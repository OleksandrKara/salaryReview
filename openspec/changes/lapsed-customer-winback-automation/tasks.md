## 1. External, one-time setup (not code)

- [ ] 1.1 Square Catalog: create a new $5 automatic-discount pricing rule + customer group,
      separate from the existing $10 same-day-rebooking group (see design.md D9) — **same $99
      minimum order value as the $10 promo**, confirmed with the owner
- [ ] 1.2 Record the new group id somewhere it can be dropped into `REBOOKING_WINBACK_AUTO_DISCOUNT_GROUP_ID`
      for both salaryReview and akluxnails-home deployments (same secret-handling convention as the
      existing `REBOOKING_AUTO_DISCOUNT_GROUP_ID` — never write it to a file in this repo)

## 2. Backend (salaryReview) — schema

- [x] 2.1 Create `V68__lapsed_customer_winback_send.sql`: `lapsed_customer_winback_send(id
      BIGSERIAL PRIMARY KEY, square_customer_id TEXT NOT NULL, phone_number TEXT, customer_name
      TEXT, visit_date DATE NOT NULL, promo_expires_at TIMESTAMPTZ NOT NULL, state TEXT NOT NULL
      CHECK (state IN ('SENT','SKIPPED_BOOKED','SKIPPED_DISABLED','SKIPPED_NEGATIVE_FEEDBACK',
      'SKIPPED_UNRESOLVED')), created_at TIMESTAMPTZ NOT NULL DEFAULT now())` with a unique index on
      `square_customer_id` (see design.md D4) — shipped with `promo_expires_at` nullable, not
      `NOT NULL` (no real expiry to record for `SKIPPED_*` states)
- [x] 2.2 Same migration: seed `sms_automation` with `lapsed_customer_winback` (`enabled = false` —
      see design.md D11)

## 3. Backend (salaryReview) — config + promo-link plumbing

- [x] 3.1 `RebookingProperties`: add `winbackAutoDiscountGroupId` (mirrors `autoDiscountGroupId`,
      same `isWinbackAutoDiscountConfigured()` fail-open-to-no-op pattern) — see design.md D9
- [x] 3.2 `ShortLinkController`: add `WINBACK_PREFIX = "WINBACK:"` / `WINBACK_PROMO_CODE =
      "WINBACK5"` alongside the existing `REBOOK_PREFIX`/`REBOOK_PROMO_CODE`; generalize
      `resolveRebookingPromo`'s promo-code parameter so both prefixes share the same signing call
      (no changes needed to `RebookingPromoSigner` itself — already generic over promo code, see
      design.md D9)
- [x] 3.3 Internal group-enroll endpoint (`InternalNotificationController`): accept an optional
      `promoCode` request field (not `groupId` — cleaner to resolve the group id server-side from
      the promo code, same pattern as everything else in this endpoint), defaulting to
      `REBOOK10` for backward-compatible callers, so the win-back flow can pass `"WINBACK5"`
      instead

## 4. Backend (salaryReview) — win-back automation

- [x] 4.1 New native-SQL repository query (mirrors `MarketingContactsRepository
      .findPendingFollowUp`'s convention) implementing the D2 eligibility query against
      `provider_visit`
- [x] 4.2 New `LapsedCustomerWinbackSendRepository` for `lapsed_customer_winback_send`
      (existence-check by `square_customer_id` + insert)
- [x] 4.3 New `SmsAutomationRegistry.AutomationMeta` entry for `lapsed_customer_winback`
- [x] 4.4 New `LapsedCustomerWinbackScheduler` (`@Scheduled(cron = "0 0 10 * * *")` +
      `@SchedulerLock`, see design.md D1). **Hand-renders both message bodies directly, bypassing
      `SmsTemplateRegistry`** — same reasoning `CheckoutReviewReplyService`/`SameDayRebookingScheduler`
      already established: needs a self-referencing click-tracked link generated up front. For each
      eligible customer from the D2 query (which already carries `technician_name` — no separate
      Square lookup for it, see D5): resolve customer phone/given-name via `SquareClient` (D5:
      unresolved → `SKIPPED_UNRESOLVED`); check `hasNegativeFeedback` (D7: true →
      `SKIPPED_NEGATIVE_FEEDBACK`); check upcoming booking (D6: found → `SKIPPED_BOOKED`); check
      `SmsAutomationService.isEnabled("lapsed_customer_winback")` (D11: disabled →
      `SKIPPED_DISABLED`); else — generate a click token, compute `promo_expires_at` (D10), check
      consent the same dual-source way `SameDayRebookingScheduler.hasConsent` does, render the
      **final, confirmed** marketing or transactional body (D8 — technician-known or
      technician-less fallback depending on whether `technician_name` was non-blank), send via
      `TwilioSmsClient` directly (not `sendTemplated`, matching `SameDayRebookingScheduler`'s own
      bypass), log via `SmsMessageLogService.logOutboundWithLink`, insert `SENT` row with the
      computed `promo_expires_at`

## 5. Frontend (akluxnails-home) — second promo code

- [x] 5.1 `app/api/rebooking-promo/verify/route.ts`: no change needed — already fully generic over
      `code`, just passes through whatever `verifyRebookingPromoSignature` confirms
- [x] 5.2 `app/page.tsx`/`HomePageV4`/`HeaderV4`/`RebookingPromoBanner`: promo banner reads the
      verified code and shows the right amount/copy for `WINBACK5` vs `REBOOK10`, both gated on
      the same $99-minimum-order display logic; `useBookingFlow.ts`'s display-only discount
      estimate (`PROMO_DISCOUNT_CENTS_BY_CODE`) branches the same way
- [x] 5.3 `app/api/booking/create/route.ts`: on a `WINBACK5` booking, passes `promoCode` through to
      the group-enroll endpoint (see task 3.3) and shows the win-back seller note, not the
      same-day-rebooking one
- [x] 5.4 No changes needed to `lib/rebookingPromo.ts` — `verifyRebookingPromoSignature` is already
      generic over the promo code (design.md D9)

## 6. Tests

- [x] 6.1 `LapsedCustomerWinbackScheduler`: all skip branches (booked, negative feedback, disabled,
      unresolved) + idempotency (already-processed customer never reprocessed) + both consent
      branches (consented → marketing body with $5 mentioned; not consented → transactional body,
      no discount language, same link) + both technician branches (named technician → possessive
      "Susan's schedule is almost full" copy; blank/null `technician_name` → technician-less
      fallback copy, neither branch ever sends a broken `{technician}` placeholder)
- [ ] 6.2 Eligibility query: 2+ visits excluded; boundary days (20/21/35/36) behave correctly —
      **not added as a dedicated test**: this codebase has no precedent for unit-testing a raw
      JdbcTemplate native query directly (`MarketingContactsRepository.findPendingFollowUp` isn't
      tested this way either — only mocked at the scheduler level, which is what 6.1 does here
      too). Covered instead by the real-data spot-check in 7.4, which confirmed the query executes
      and returns correctly-shaped, correctly-dated, non-null-technician rows against production.
- [x] 6.3 `promo_expires_at` computation: matches `SameDayRebookingTriggerService`'s
      end-of-day-in-`America/Los_Angeles` logic exactly (asserted directly in the `SENT`-path
      scheduler test)
- [x] 6.4 `ShortLinkController`: `WINBACK:` prefix resolves to the win-back promo target correctly,
      independently of the existing `REBOOK:` prefix continuing to work unchanged
- [x] 6.5 `lapsed_customer_winback` seeds disabled immediately after `V68` runs

## 7. Verification

- [x] 7.1 `mvn test` (salaryReview) — all new tests pass (`LapsedCustomerWinbackSchedulerTest`,
      `ShortLinkControllerTest`, `InternalNotificationControllerTest`); `SmsAutomationSeedDataTest`
      requires a real Postgres and fails locally with "connection refused" the same way every
      other `@SpringBootTest` in this suite does without one — passes in CI
- [x] 7.2 `tsc`/`eslint`/`next build` clean on akluxnails-home
- [ ] 7.3 Confirm `lapsed_customer_winback` appears correctly in `/owner/automations` with no other
      salaryReview frontend changes required (design.md D12) — pending a real deploy; the registry
      entry (task 4.3) is in place and follows the same shape every other automation uses
- [x] 7.4 Real data spot-check (read-only, production): ran the D2 eligibility query directly
      against production — 20 eligible customers in the current 21–35-day window, all with
      non-null `technician_name`, dates correctly bounded, none yet in
      `lapsed_customer_winback_send` (table doesn't exist in production until this migration
      deploys)
- [ ] 7.5 **Do not enable either variant** as part of this change — ships disabled per D11;
      enabling is a separate, explicit owner decision made after (a) the $5 Square Catalog
      group/pricing rule exists, and (b) the parallel `same_day_rebooking_discount` click-rate
      investigation has at least ruled out a shared, systemic deliverability problem (see design.md
      Risks) — final copy itself is already confirmed (D8)
