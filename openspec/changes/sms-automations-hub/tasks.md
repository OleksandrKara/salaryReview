## 1. Spike — confirm real Square payload shapes before writing filter logic

- [ ] 1.1 Against a real sandbox (or production, read-only) in-salon POS sale, confirm the exact
      `payment.updated` webhook payload shape and the Order field(s) that distinguish a
      booking-linked order from a walk-in POS order (see design.md D2/Risks) — do this before 3.x
- [ ] 1.2 Confirm `GET /v2/customers/{id}` actually returns a usable phone number field for the
      salon's real customer records (some may only have email/no phone) — confirms D2's "no phone
      on file → silent skip" path is the exception, not the common case

## 2. Backend (salaryReview) — schema

- [ ] 2.1 Create `V52__sms_automations.sql`:
      `sms_automation(automation_key TEXT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT false,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by TEXT)` — column defaults to
      `false` (see design.md D8); seeded with `four_hand_request` (`enabled = true`, already live)
      and `checkout_review_request` (`enabled = false`, must be turned on explicitly after testing)
- [ ] 2.2 Same migration: `sms_message(id BIGSERIAL PRIMARY KEY, direction TEXT NOT NULL
      CHECK (direction IN ('OUTBOUND','INBOUND')), automation_key TEXT REFERENCES
      sms_automation(automation_key), phone_number TEXT NOT NULL, template_key TEXT, body TEXT NOT
      NULL, twilio_message_sid TEXT, status TEXT NOT NULL, reason TEXT, link_target TEXT, clicked_at
      TIMESTAMPTZ, read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now())` — `read_at`
      is only ever set on `INBOUND` rows (see design.md D9)
- [ ] 2.3 Same migration: `sms_reply_flow(id BIGSERIAL PRIMARY KEY, automation_key TEXT NOT NULL
      REFERENCES sms_automation(automation_key), phone_number TEXT NOT NULL, customer_name TEXT,
      state TEXT NOT NULL CHECK (state IN ('AWAITING_SEND','AWAITING_REPLY','COMPLETED','EXPIRED')),
      send_due_at TIMESTAMPTZ NOT NULL, reply_expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT
      NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`, with an index on
      `(state, send_due_at)` and `(phone_number, state)` for the two poller queries
- [ ] 2.4 Partial index on `sms_message (direction, read_at) WHERE direction = 'INBOUND' AND
      read_at IS NULL` — backs the hub's unread-count query cheaply

## 3. Backend (salaryReview) — registry + logging (modifies sms-automation-platform)

- [ ] 3.1 Add `automationKey` (nullable) to `SmsTemplate`; set it on `four_hand_request_received`
      (`"four_hand_request"`)
- [ ] 3.2 New `SmsAutomationRegistry` (or extend `SmsTemplateRegistry`) — `isEnabled(automationKey):
      boolean` backed by `sms_automation`
- [ ] 3.3 `TwilioSmsService.sendTemplated`: check `isEnabled` for the template's `automationKey`
      before sending (disabled → `{sent:false, reason:"automation_disabled"}`); after every attempt
      (any outcome), insert one `sms_message` row (`direction=OUTBOUND`)
- [ ] 3.4 New `SmsAutomationConfigRepository`/service — list all automations with enabled state,
      toggle one by key (OWNER-only, writes `updated_by`)

## 4. Backend (salaryReview) — Square webhook receiver

- [ ] 4.1 `SquareWebhookProperties` (`square.webhook.signature-key`, blank = reject everything, same
      "no sensible open default" shape as `InternalApiProperties`)
- [ ] 4.2 `SquareWebhookController`: `POST /api/public/webhooks/square` (`permitAll()`), verifies
      `x-square-hmacsha256-signature` (HMAC-SHA256 of notification URL + raw body,
      `MessageDigest.isEqual`-style constant-time compare), 401 on failure
- [ ] 4.3 On a valid `payment.updated` event with `status: COMPLETED`: fetch the Order (extend
      `SquareClient.Order`/add a method per task 1.1's findings), skip if booking-linked, else
      resolve `customer_id` → phone (extend `SquareClient.Customer`/add
      `customerPhone(customerId)` per task 1.2), skip silently if no phone
- [ ] 4.4 On a qualifying event: insert one `sms_reply_flow` row
      (`automation_key="checkout_review_request"`, `state=AWAITING_SEND`,
      `send_due_at = now() + 2 minutes`) — idempotent on Square's own event id if it provides one
      (avoid a double-enqueue on a Square retry-delivery)

## 5. Backend (salaryReview) — delayed send + inbound reply + branching

- [ ] 5.1 `SmsReplyFlowScheduler` (`@Scheduled(fixedDelay=15000)`): send due `AWAITING_SEND` rows'
      rating SMS (new `TRANSACTIONAL` template `checkout_rating_request`, copy: *"Hi {{name}}, on a
      scale of 1 to 5, how did you like your nails today? 💅 Just reply with a number."* — no name
      falls back to a name-less variant), flip to `AWAITING_REPLY` with `reply_expires_at = now() +
      24h`; separately flip past-due `AWAITING_REPLY` rows to `EXPIRED`
- [ ] 5.2 `TwilioInboundProperties`/verification: `X-Twilio-Signature` (HMAC-SHA1 of full webhook
      URL + sorted form params, using the Twilio Auth Token) — reuse whichever auth-token source
      `TwilioSmsClient` already reads
- [ ] 5.3 `TwilioInboundSmsController`: `POST /api/public/sms/inbound` (`permitAll()`), verifies
      signature, logs the inbound `sms_message` row unconditionally, looks up the newest
      `AWAITING_REPLY` row for the sender's normalized phone number
- [ ] 5.4 Branch: body contains digit `5` → send the Google-review short-link message
      (`checkout_review_positive`, `TRANSACTIONAL`); otherwise → the feedback-form short-link
      message (`checkout_review_negative`, `TRANSACTIONAL`); either way flip the row to `COMPLETED`
- [x] 5.5 `ShortLinkController`: `GET /r/{token}` (`permitAll()`), `token` = opaque
      `ClickTokens`-generated string stored on `sms_message.click_token` (revised from the raw
      `sms_message.id` — see design.md D6, V53), stamp `clicked_at` if unset, `302` to the fixed
      Google-review or feedback-form URL per that row's `link_target`

## 6. Backend (salaryReview) — owner-facing API

- [ ] 6.1 `SmsAutomationController` — `GET /api/owner/automations` (list with enabled state + audience
      description + 30-day sent count per key), `PUT /api/owner/automations/{key}` (toggle enabled)
- [ ] 6.2 `SmsActivityController` — `GET /api/owner/automations/activity` (paginated, filterable by
      phone number/direction/automation key), `GET /api/owner/automations/activity/unread-count`,
      `POST /api/owner/automations/activity/{id}/read` (idempotent — marking an already-read message
      read again is a no-op, doesn't error)

## 7. Backend (salaryReview) — tests

- [x] 7.1 Square webhook signature verification: valid signature accepted, missing/wrong signature
      → 401, no side effects (`SquareWebhookControllerTest`)
- [x] 7.2 In-salon vs booking-linked order filter: booking-linked → no flow row created; walk-in →
      flow row created; no phone on file → no flow row created (`CheckoutReviewTriggerServiceTest`,
      also covers idempotency on Square redelivery and no-customer/order-not-found paths)
- [x] 7.3 `SmsReplyFlowScheduler`: not-yet-due row untouched; due row sends and transitions state;
      past-expiry `AWAITING_REPLY` row transitions to `EXPIRED` and no longer matches an inbound
      reply (`SmsReplyFlowSchedulerTest`)
- [x] 7.4 Twilio inbound signature verification (same shape as 7.1) (`TwilioInboundSmsControllerTest`)
- [x] 7.5 Reply branching: body containing "5" → positive branch; body without "5" → negative
      branch; no matching `AWAITING_REPLY` row → logged, no send, no exception
      (`TwilioInboundSmsControllerTest`, `CheckoutReviewReplyServiceTest`)
- [x] 7.6 `ShortLinkController`: first click stamps `clicked_at` and redirects; second click
      redirects again without overwriting the original timestamp (`ShortLinkControllerTest`)
- [x] 7.7 Registry gate: disabled automation → `{sent:false, reason:"automation_disabled"}`,
      confirmed for both `four_hand_request` and `checkout_review_request` (`TwilioSmsServiceTest`)
- [x] 7.8 Every outbound/inbound path from 7.1–7.5 asserts a matching `sms_message` row was written
      (covered across `TwilioSmsServiceTest`, `TwilioInboundSmsControllerTest`,
      `CheckoutReviewReplyServiceTest`, `SmsMessageLogServiceTest`)
- [x] 7.9 `checkout_review_request` is disabled immediately after the `V52` migration runs (seed
      data assertion) — confirms D8's "ships disabled" guarantee at the data level, not just in the
      migration file's intent (`SmsAutomationSeedDataTest`, requires a real Postgres like
      `SalonreviewApplicationTests` — passes in CI)
- [x] 7.10 Read/unread: a new inbound row has `read_at = null` and is counted by the unread-count
      endpoint; marking it read sets `read_at` and removes it from the count; marking an
      already-read row read again is a no-op (doesn't change the original `read_at`, doesn't error)
      (`SmsMessageLogServiceTest`)

## 8. Frontend (salaryReview) — Automations hub

- [x] 8.1 Types + `serverApi`/`api.ts` methods for all new owner endpoints (list, toggle, activity,
      unread-count, mark-read)
- [x] 8.2 Proxy routes under `app/api/owner/automations/**`
- [x] 8.3 `/owner/automations/page.tsx` — automation cards (name, audience description, enabled
      toggle, 30-day sent count); single-column stack on mobile, grid on desktop; toggle is a
      full-size touch target, not shrunk to fit (see design.md D10)
- [x] 8.4 Inbox/Activity view (shared component, filterable by phone/direction/automation) —
      `sm:hidden` card-per-message list on mobile (sender, snippet, timestamp, unread dot) + `hidden
      sm:block` dense table on desktop, mirroring `LtvView`'s existing split; unread rows visually
      distinct (bold/dot), a click marks read
- [x] 8.5 Unread-count badge on the `/owner/automations` nav entry itself, visible from anywhere in
      the app, not just once already on the page
- [x] 8.6 Nav entry for `/owner/automations` (top-level, not under Settings — see design.md D7)

## 9. External, one-time setup (not code)

- [ ] 9.1 Square Developer Dashboard: subscribe to `payment.updated` for this location, point at the
      new webhook URL, record the signature key into `SQUARE_WEBHOOK_SIGNATURE_KEY`
- [ ] 9.2 Twilio Console: set the toll-free number's inbound-SMS webhook URL to the new endpoint
- [ ] 9.3 Confirm the two fixed destination URLs (Google Maps review link, feedback Google Form
      link) are exactly right before they go live in code (owner already provided both in chat)

## 10. Verification

- [x] 10.1 `mvn test` — all new + existing tests pass (470/473; the 3 failures are
      `SalonreviewApplicationTests`/`SmsAutomationSeedDataTest` needing a real Postgres, same
      pre-existing local-only limitation as before this change — verified clean against a real
      pgvector-enabled Postgres, and CI runs one)
- [x] 10.2 `tsc`/`eslint`/`next build` clean on the frontend
- [ ] 10.3 One real in-salon test transaction (small/refundable amount) on production Square:
      confirm the rating SMS arrives ~2 minutes later, reply "5" and confirm the Google review link
      arrives and its click is logged; repeat with a non-"5" reply and confirm the feedback-form
      link instead
- [ ] 10.4 Confirm toggling `checkout_review_request` off via the hub genuinely stops new flows from
      being enqueued (existing in-flight `AWAITING_REPLY` rows still get their branch reply — decide
      and document this edge case when 3.3/5.1 are implemented, since disabling mid-flight is not
      explicitly specified above)
