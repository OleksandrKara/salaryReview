## 1. Backend (salaryReview) — schema

- [ ] 1.1 Create `V54__lead_followup_send.sql`: `lead_followup_send(id BIGSERIAL PRIMARY KEY,
      contact_id UUID NOT NULL REFERENCES marketing.contacts(id), phone_number TEXT NOT NULL,
      state TEXT NOT NULL CHECK (state IN ('SENT','SKIPPED_BOOKED','SKIPPED_DISABLED')), created_at
      TIMESTAMPTZ NOT NULL DEFAULT now())` with a unique index on `contact_id` (see design.md D3)
- [ ] 1.2 Same migration: seed `sms_automation` with `lead_follow_up` (`enabled = false` — see
      design.md D5)

## 2. Backend (salaryReview) — lead-follow-up automation

- [ ] 2.1 New `SmsTemplateRegistry` entry `lead_follow_up_nudge` (TRANSACTIONAL,
      `automationKey="lead_follow_up"`, name-less-greeting fallback — see design.md D4 for exact copy)
- [ ] 2.2 New `MarketingContact`-reading repository query (against the existing
      `marketing.contacts` JPA mapping, read-only) implementing the D1 poll query
- [ ] 2.3 New `LeadFollowUpRepository` for `lead_followup_send` (mirrors `SmsReplyFlowRepository`'s
      shape, simpler — no state-transition queries needed, just existence-check + insert)
- [ ] 2.4 New `LeadFollowUpScheduler` (`@Scheduled(fixedDelay=15_000)`): runs the D1 query, and for
      each contact found: resolve a Square customer for the contact's phone number
      (`contact.squareCustomerId()` if already set, else a live `SquareClient.customerIdsForPhone`
      lookup — see design.md D2) and check for any upcoming, not-cancelled booking via
      `SquareClient.bookingsForCustomer` + the existing `didHappen`/future-`startAt` filter; if one
      exists → insert `SKIPPED_BOOKED` row, no send; else check
      `SmsAutomationService.isEnabled("lead_follow_up")` → if disabled, insert `SKIPPED_DISABLED`
      row, no send; else send via `TwilioSmsService.sendTemplated`, insert `SENT` row regardless of
      the send's own outcome (the activity log already captures send success/failure per-message —
      this table only needs to track "did we already consider this contact," not duplicate that)

## 3. Backend (salaryReview) — MANAGER read/reply access

- [ ] 3.1 `SecurityConfig`: allow MANAGER on `GET /api/owner/automations/activity/**` (existing
      endpoints) and the two new endpoints below — `PUT /api/owner/automations/{key}` (toggle)
      stays OWNER-only (see design.md D6)
- [ ] 3.2 New `SmsActivityController` endpoint: `GET /api/owner/automations/activity/conversations`
      — one row per distinct `phone_number`, most-recent-message-first, with unread count and last
      message preview (backs the contact list in the new UI)
- [ ] 3.3 New endpoint: `GET /api/owner/automations/activity/conversations/{phoneNumber}` — full
      chronological thread for one phone number
- [ ] 3.4 New endpoint: `POST /api/owner/automations/activity/reply` — `{ phoneNumber, body }`,
      sends via `TwilioSmsClient` directly (bypassing templates/automation gating, see design.md
      D9), logs via `SmsMessageLogService.logOutbound` with `automationKey=null`

## 4. Frontend (salaryReview) — manager conversation view

- [ ] 4.1 New page `/admin/messages` (see design.md D7) — contact list (mobile: full-width list,
      tap to open a thread; desktop: two-column, list + selected thread, mirroring a normal texting
      app's layout) + reply composer at the bottom of the open thread
- [ ] 4.2 Nav entry for `/admin/messages`, visible to both OWNER and MANAGER (matches the `/admin/*`
      convention already used for `mgrRedos`/`navManualAdjustments`)
- [ ] 4.3 Unread-count badge on the nav entry itself (same pattern as the existing
      `/owner/automations` badge — see `sms-automations-hub` tasks.md 8.5), visible to both roles
- [ ] 4.4 Types + `serverApi`/`api.ts` methods + proxy routes for the three new endpoints

## 5. Tests

- [ ] 5.1 `LeadFollowUpScheduler`: contact with no booking + automation enabled → sends, `SENT` row
      written; contact with a booking → `SKIPPED_BOOKED`, no send attempt; automation disabled →
      `SKIPPED_DISABLED`, no send attempt; a contact already in `lead_followup_send` is never
      reprocessed (idempotency)
- [ ] 5.2 `lead_follow_up` seeds disabled immediately after `V54` runs (same shape as
      `SmsAutomationSeedDataTest` from `sms-automations-hub`, requires a real Postgres)
- [ ] 5.3 MANAGER role: can read activity/conversations endpoints and send a manual reply; cannot
      hit the automation toggle endpoint (403, not 200)
- [ ] 5.4 Manual reply endpoint: sends via `TwilioSmsClient`, logs with `automationKey=null`,
      `templateKey=null`; Twilio-not-configured and send-failure paths both log without throwing
      (same never-throw shape as every other send path in this codebase)
- [ ] 5.5 Conversation-list/thread endpoints: correct grouping by phone number, correct ordering,
      correct unread counts

## 6. External, one-time setup (not code)

- [ ] 6.1 Twilio Console: configure the toll-free number's Voice URL to forward calls to the Ooma
      line (+16193231185) via a TwiML `<Dial>` response — no salaryReview code involved, needs
      explicit confirmation before touching the live Twilio configuration (same standing rule as
      every other live external-service change this session)

## 7. Verification

- [ ] 7.1 `mvn test` — all new + existing tests pass (same "3 no-local-DB failures expected
      locally, clean in CI" pattern as every prior change)
- [ ] 7.2 `tsc`/`eslint`/`next build` clean on the frontend
- [ ] 7.3 Real E2E check: submit a real (or throwaway) contact via the akluxnails-home homepage,
      confirm the follow-up text arrives ~2 minutes later if no booking is made, confirm it's
      correctly skipped if a booking is made within that window; confirm a MANAGER-role login can
      see and reply to a conversation, and cannot toggle the automation on/off
