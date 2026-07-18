## Why

mani and akluxnails-home now alert *staff* on Telegram when a 4-hand request comes in (see the
`telegram-four-hand-notify`-era work), but the *customer* gets no confirmation at all beyond the
on-screen message — no text, nothing to refer back to while waiting for a callback. More broadly,
the owner wants an SMS automation capability that will grow over the next few months (post-visit
win-back offers, abandoned-lead follow-up, long-dormant-client re-engagement), and today there is
no app in this stack that owns any outbound SMS, no Twilio integration, and no enforced rule
keeping promotional SMS content out of messages sent to contacts who never opted in to marketing
texts.

This change picks the one app that should own SMS sending going forward, builds the reusable
sending/compliance path, and ships the first real automation on top of it: a transactional
confirmation text when a customer submits a 4-hand request.

## What Changes

- **Ownership decision**: salaryReview becomes the sole owner of Twilio credentials and the only
  app that ever calls Twilio's API. mani and akluxnails-home trigger sends by calling a new
  internal, template-keyed relay endpoint — the same shape as the existing Telegram relay — so
  neither booking app ever sees the Twilio Account SID/API Key/Secret.
- **Compliance gate, enforced in code, not just by convention**: every SMS template is registered
  in salaryReview with a fixed `messageClass` (`TRANSACTIONAL` or `MARKETING`) that the *caller
  cannot override*. `MARKETING`-class sends are checked against `marketing.contacts
  .sms_marketing_consent` before sending and silently skipped (never thrown) if the contact hasn't
  opted in. `TRANSACTIONAL`-class sends go out regardless of consent, matching how Square's own
  SMS reminders work (the reference behavior the owner asked to mirror) — see design.md D2 for the
  exact rule.
- **New owner-only settings page** `/owner/settings/sms` to enter the Twilio Account SID, API Key,
  API Secret, and sending phone number once the owner adds them (not yet available — ships with a
  functioning empty state that silently no-ops, exactly like the Telegram settings page did before
  a token existed).
- **First automation**: a `TRANSACTIONAL` "we got your 4-hand request" confirmation SMS, sent
  immediately when mani or akluxnails-home's 4-hand endpoint succeeds (same trigger point as the
  existing Telegram staff alert).
- **Roadmap capabilities documented, not built here** (see design.md's Open Questions and the
  table below) — each is its own future openspec change:

  | # | Trigger | Message | Class | Why deferred |
  |---|---------|---------|-------|---------------|
  | 1 | 4-hand request submitted | "We got your request, we'll call you shortly" | TRANSACTIONAL | **Built in this change** |
  | 2 | ~3 min after a Square appointment is marked paid/completed | Feedback ask + 10%-off rebooking link/coupon, both expiring in 2h | MARKETING (real consent required) | Needs a near-real-time "checkout happened" signal that nothing in this stack has today (no polling job runs more than once/day) — needs a Square webhook receiver, which is a first-of-its-kind addition here. Also needs a coupon/expiring-link generator. Separate change. |
  | 3 | Contact captured (name+phone) but no appointment within some window | "Still want to book? Here's a link" | Likely TRANSACTIONAL if it's a pure abandoned-flow nudge with no discount; MARKETING if any incentive is added | Needs its own trigger design (delay window, how "no appointment yet" is detected) |
  | 4 | Contact hasn't rebooked in 3+ weeks (win-back segment) | Re-engagement message, likely with an incentive | MARKETING (real consent required) | This one *does* fit the existing daily-cron precedent (`RevenueSnapshotScheduler`/`ProviderVisitScheduler`) — easiest of the three to build once the sending path below exists, but still its own change (segment definition, owner review of copy) |

## Non-goals

- No Twilio account/credentials are configured in this change — the owner will supply them later;
  this change ships the storage + settings UI + sending path in a state that works correctly with
  them absent (silent no-op, same as Telegram before a token was set).
- No implementation of roadmap items #2–#4 above — each needs its own design decisions (webhook
  receiver, coupon/link expiry mechanism, segment query) that shouldn't block shipping the
  foundation + the one automation that's ready today.
- No generic "marketing campaign builder" UI — templates are still plain code (matching this
  codebase's existing "no CMS yet, edit on request" convention for `services-config.ts`/
  `SERVICE_GROUPS`), not owner-editable copy.
- No changes to how Telegram alerting works — this is a second, independent notification channel
  reusing the same internal-relay *pattern*, not the same code path.

## Capabilities

### New Capabilities

- `sms-automation-platform`: salaryReview-owned Twilio integration with an owner-editable
  credentials page, a template registry that fixes each message's compliance class server-side,
  and an internal relay endpoint that mani/akluxnails-home call to trigger a send. Ships with one
  live template: the 4-hand-request confirmation SMS.

### Modified Capabilities

*(none)*

## Impact

- **Backend (salaryReview)**: new `com.salonreview.sms` package (`TwilioSmsConfig` entity/repo/
  service mirroring `TelegramNotificationConfig`, `TwilioSmsClient` hand-rolled HTTP client
  matching `VoyageClient`/`TelegramNotificationService`'s style, `SmsTemplateRegistry`,
  `TwilioSmsService`), new `V46__twilio_sms_config.sql` migration, a new `POST
  /api/internal/notifications/sms/send` method added to the existing
  `InternalNotificationController` (reuses the same `X-Internal-Api-Key` already set up for
  Telegram — no new shared secret), a new `TwilioSmsSettingsController` at
  `/api/owner/settings/sms` (covered by the existing `/api/owner/**` → OWNER matcher, no
  `SecurityConfig` change needed).
- **Frontend (salaryReview)**: new `/owner/settings/sms` page mirroring the Telegram settings page
  exactly (masked secret fields, null-vs-empty-string semantics on save).
- **mani + akluxnails-home**: new `lib/sms.ts` / `app/integrations/sms/notifier.py` calling the
  internal relay, invoked right alongside the existing Telegram notifier in the 4-hand submit path.
  Neither app gains any new environment variables besides reusing `INTERNAL_API_KEY`/
  `INTERNAL_API_BASE_URL` (mani) / `SALARYREVIEW_INTERNAL_BASE_URL` (akluxnails-home) already set
  up for Telegram.
- **Dependencies**: none new — Twilio's REST API is called directly over HTTPS with the existing
  `java.net.http.HttpClient` convention, no Twilio SDK.
- **Verification**: unit tests for `SmsTemplateRegistry` (class is fixed per key, not caller-
  settable), `TwilioSmsService` (consent gate for MARKETING vs unconditional send for
  TRANSACTIONAL, both paths never throwing), `InternalNotificationController`'s new endpoint
  (401 on bad key, 200 with `sent`/`reason` otherwise). Manual check: with real Twilio credentials
  once provided, submit one real 4-hand request against Square Sandbox and confirm an SMS arrives.
