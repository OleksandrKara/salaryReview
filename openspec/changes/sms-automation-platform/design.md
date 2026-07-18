## Context

Three apps share one Postgres instance: `salonLandings` ("mani", Python/FastAPI, owns the
`marketing` schema's DDL), `akluxnails-home` (Next.js), and `salaryReview` (Java/Spring, the
owner-facing operations/analytics portal, Flyway-migrated own schema, head is currently **V45**).
Both booking apps already write `marketing.contacts` rows including `sms_marketing_consent`/
`email_marketing_consent` (nullable booleans, mirrored onto the Square customer as a
"convenience" custom attribute but authoritative in Postgres — see
`salonLandings/backend/app/integrations/marketing_db/migrations.py:152,192-210`), and
salaryReview already reads that exact column read-only for the Contacts tab
(`MarketingContactsRepository.java:98,111,243-244`).

This session already established a working pattern for "a booking app needs to trigger a
notification, but shouldn't hold the third-party credential itself": the Telegram 4-hand-request
alert, where salaryReview holds the bot token and exposes
`POST /api/internal/notifications/four-hand-request` gated by a shared `X-Internal-Api-Key`
(`InternalApiProperties`/`InternalNotificationController`). This change reuses that exact shape
for SMS.

Confirmed by direct code search: **no job in this stack polls more than once a day** — the only
scheduled work is `RevenueSnapshotScheduler` (01:30 salon-local) and `ProviderVisitScheduler`
(02:00 salon-local), both whole-month re-aggregations, neither capable of detecting "an
appointment was just marked paid" within minutes. **No app has any public inbound webhook
endpoint today**, for Square or anything else.

## Goals / Non-Goals

**Goals:**
- Decide, once, which app owns Twilio credentials and outbound SMS sending, and make that
  decision hard to accidentally violate later (mani/akluxnails-home should have no code path that
  could send SMS directly).
- Make the marketing-vs-transactional distinction a server-side, per-template fact that a caller
  cannot override — not a convention callers are trusted to follow correctly.
- Ship the one automation that's ready today (4-hand confirmation) end-to-end, with the sending
  path fully correct even though real Twilio credentials don't exist yet.
- Leave the three roadmap automations (see proposal.md) clearly scoped as *future* changes with
  enough of a paper trail here that their own design docs don't have to re-litigate ownership.

**Non-Goals:**
- Building the Square webhook receiver, coupon/expiring-link system, or win-back segment query —
  each is real, separate design work (see Open Questions).
- A templating UI — templates stay in code.

## Decisions

### D1: salaryReview owns Twilio credentials and all outbound SMS

**Decision**: All Twilio API calls happen from salaryReview's backend. mani and akluxnails-home
call a new internal endpoint with a template key + variables; they never hold or see the Account
SID/API Key/Secret.

**Rationale**:
- Direct precedent already exists and works (Telegram) — same shape, same
  `X-Internal-Api-Key` secret, no new cross-app auth mechanism needed.
- salaryReview already has read access to `marketing.contacts.sms_marketing_consent` — the
  consent check lives right next to the data it needs, with no new cross-schema access to grant.
- salaryReview is the only app in this stack with any scheduled-job precedent
  (`SchedulingConfigurer`/`CronTrigger`, resolved to the salon's actual timezone) — every roadmap
  automation except #1 (this change) needs scheduled or event-driven server-side logic, not
  request/response logic, which fits a long-running Spring service far better than mani's
  request-scoped FastAPI handlers or akluxnails-home's serverless Next.js routes.

**Alternatives considered**:
- *mani owns it*: rejected — would make akluxnails-home depend on mani's uptime for its own SMS
  (an awkward peer dependency, the opposite of today's clean "both booking apps relay to the one
  owner-facing app" shape), and mani has no existing scheduled-job or broad-contacts-read
  precedent to build the segment-based roadmap items on.
- *akluxnails-home owns it*: rejected for the same reasons, plus it's the least natural home for
  cron-like segment jobs of the three apps.
- *A new, dedicated notifications microservice*: rejected as premature — this is a small salon's
  stack; a 4th deployable service (new blue/green setup, new CI/CD, new monitoring) to own two
  lightweight relays isn't worth the operational overhead at this scale.

### D2: Compliance class is fixed per template key, server-side — never caller-supplied

**Decision**: Every SMS template registered in `SmsTemplateRegistry` has a fixed `messageClass`
(`TRANSACTIONAL` or `MARKETING`) baked in at registration, not passed by the caller. The internal
endpoint's request body is `{templateKey, phoneNumber, variables}` — no `messageClass` field
exists on the wire at all. `TwilioSmsService.sendTemplated(...)`:
1. Looks up the template by key; unknown key → `{sent: false, reason: "unknown_template"}`.
2. If `messageClass == MARKETING`: reads `sms_marketing_consent` for that phone number from
   `marketing.contacts` (plain `JdbcTemplate`, matching `MarketingContactsRepository`'s existing
   style — never a JPA entity against a schema this app doesn't own, per every prior `marketing.*`
   access in this codebase). Not `true` → `{sent: false, reason: "no_consent"}`, logged at `info`,
   never thrown.
3. Otherwise renders the template with `variables` and calls Twilio. Any Twilio-side failure is
   caught and returns `{sent: false, reason: "send_failed"}` — never throws, matching every other
   notification path in this codebase (Telegram, Voyage-adjacent conventions for third-party
   clients).

**Rationale**: this is exactly the compliance rule the owner asked to have enforced (see the
persisted memory this session, `sms_compliance_rule` — promotional content requires real opt-in
consent, transactional content doesn't, mirroring Square's own SMS behavior). Making it
server-side and keyed off a registry entry — rather than a boolean the caller passes — means a
future caller (or a copy-paste mistake in mani/akluxnails-home) can't accidentally mark a
promotional message as transactional to bypass the consent check. The only way to add a new
promotional template is to add a new registry entry in salaryReview itself, where the check is
unconditionally applied.

**The one template shipped in this change**: `four_hand_request_received` — `TRANSACTIONAL`. No
discount, no promo, no urgency language. Example body (final copy is the owner's call before
go-live, not fixed by this spec): *"Hi {{name}}, we got your 4-Hand request for {{preferredTime}}!
We'll call you shortly to confirm the exact time & pricing. — AK.LUX.NAILS"*.

### D3: Internal endpoint reuses the existing Telegram controller and shared secret

**Decision**: Add `POST /api/internal/notifications/sms/send` as a new method on the *existing*
`InternalNotificationController` (not a new controller class) — same `X-Internal-Api-Key` check
already in place for the Telegram endpoint, same `permitAll()` matcher in `SecurityConfig`
(`/api/internal/**`, already covers this path, no change needed there). Response shape:
`{"sent": boolean, "reason": string | null}` — `reason` is populated on every non-send outcome
(`no_consent`, `unknown_template`, `not_configured`, `send_failed`) for observability, matching
the "never a bare 500 for an expected non-send" contract already established for Telegram.

**Rationale**: one internal-notifications surface for multiple channels is simpler than
proliferating near-identical auth-checking controllers per channel, and the shared secret is
already provisioned identically on all three apps' `.env` files from the Telegram work — no new
secret to generate or distribute.

### D4: Twilio credential storage mirrors `telegram_notification_config` exactly

**Decision**: New migration `V46__twilio_sms_config.sql` — single-row table
`twilio_sms_config(id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id), account_sid TEXT, api_key TEXT,
api_secret TEXT, from_phone_number TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_by TEXT)`, seeded with all four credential fields `NULL` (never a live secret in a
git-committed migration — same reasoning as `V45`). `TwilioSmsConfig` entity/repository/service
are a direct structural copy of `TelegramNotificationConfig`/`TelegramConfigService` (same
null-vs-empty-string update semantics: `null` field = leave unchanged, `""` = clear).

**Twilio auth shape, confirmed against Twilio's actual API** (the three values the owner said
they'll provide map exactly): HTTP Basic Auth to `POST
https://api.twilio.com/2010-04-01/Accounts/{account_sid}/Messages.json`, with **username =
`api_key`** (an API Key SID, starts `SK...`) and **password = `api_secret`** — the Account SID
(`AC...`) is *only* the URL path segment, not part of the Basic Auth pair. `TwilioSmsClient`
mirrors `TelegramNotificationService`'s hand-rolled `java.net.http.HttpClient` shape (5s connect
timeout, no SDK dependency).

**Owner settings page** `/owner/settings/sms`, structurally identical to
`/owner/settings/telegram`: GET returns masked `api_key`/`api_secret` (last-4 only) plus the
unmasked `from_phone_number` (not secret) and whether credentials are set; PUT never round-trips
a masked value back.

### D5 (deferred): near-real-time "checkout completed" trigger for roadmap item #2

Explicitly **not decided or built here** — flagged so the future design doc for the post-checkout
coupon SMS doesn't start from zero:
- **Option A — Square webhook receiver**: subscribe to Square's `booking.updated`/payment-related
  webhook events, verify Square's HMAC signature, react within seconds. Correct and timely (the
  "3 minutes, then a 2-hour-expiring coupon" design is inherently time-sensitive), but a genuine
  first for this stack — no app has a public inbound webhook endpoint today, so this means new
  signature-verification code, new Square Developer Dashboard configuration, and new
  idempotency/retry handling with no existing pattern to copy.
- **Option B — a new, much tighter poll** (e.g. every 1–2 minutes) against Square's
  bookings/payments search API, scoped to "recently completed," decoupled from the existing daily
  payroll-oriented jobs. Simpler (no public endpoint, no signature verification, no Dashboard
  config) but adds recurring Square API load and a couple of minutes of extra latency on top of an
  already-tight delay budget, and still isn't truly real-time.
- Recommendation for whoever writes that change: Option A, given the time-sensitivity of an
  expiring coupon — but this is that change's decision to make, not this one's.

## Risks / Trade-offs

- **Twilio credentials don't exist yet** — every piece of this ships in a state that must degrade
  correctly with them absent: `not_configured` reason, no exception, no send attempt. This is the
  same shape Telegram shipped in before its token existed, already proven in production.
- **`marketing.contacts.sms_marketing_consent` is nullable, not a tri-state the schema enforces**
  — `NULL` is treated as "not consented" (fail closed) everywhere a MARKETING check happens, same
  as how the existing Contacts tab already treats it.
- **This change's own template (`four_hand_request_received`) is TRANSACTIONAL**, so it does *not*
  exercise the consent-gate code path in production immediately — that path is fully covered by
  unit tests instead, and will get its first real production exercise once a MARKETING-class
  roadmap template ships.

## Open Questions

- Should `/owner/settings/telegram` and `/owner/settings/sms` be consolidated into a single
  `/owner/settings` hub page once a third integration exists? Not needed yet with only two —
  revisit if a third settings page gets added.
- Roadmap item #3 (contact-captured-but-no-appointment nudge) needs its own trigger-timing
  decision (how long to wait, and how "no appointment yet" is determined given a contact might
  book through either mani or akluxnails-home) — deliberately left open for that change.
- Roadmap item #4 (3+ week win-back segment) fits the existing daily-cron precedent cleanly and
  is likely the next-easiest to build on top of this foundation — but still needs its own segment
  definition (what counts as "hasn't rebooked") and, being MARKETING-class, real owner sign-off on
  copy before it can fire.
