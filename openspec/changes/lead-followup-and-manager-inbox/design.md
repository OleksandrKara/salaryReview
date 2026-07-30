## Context

Builds directly on `sms-automations-hub` (registry/logging/hub) and reuses its infrastructure:
`SmsAutomationService` (enable/disable + registry), `SmsMessageLogService` (the `sms_message`
activity log), `TwilioSmsService`/`TwilioSmsClient` (sending), and the existing
`telegram-inbound-sms-alert` change (managers already get pinged on any inbound text). This change
adds one new automation trigger (contact-capture-without-booking) and one new access surface
(MANAGER read/reply), without modifying the checkout-review-request automation itself.

## Goals / Non-Goals

**Goals**: (1) text a lead who hasn't booked within 2 minutes of leaving contact info, purely to
offer help, no incentive; (2) let managers see and reply to any customer conversation from their
own account, grouped per customer, not just a flat filterable log; (3) keep both additions
consistent with existing conventions (ships-disabled, transactional-vs-marketing classification,
mobile-first UI).

**Non-goals**: pulling in Square/Ooma message history; a full CRM; per-staff-member reply
attribution; changing the checkout-review automation's own logic; anything with phone calls.

## Decisions

### D1: Trigger is a poll against `marketing.contacts`, not a webhook

**Decision**: akluxnails-home writes directly to the shared `marketing.contacts`/`marketing.submissions`
tables via raw SQL (see `lib/marketingContacts.ts`) — there is no webhook or API call back to
salaryReview when a contact is captured, unlike Square's `payment.updated` webhook. So detecting
"a new contact just showed up" has to be a poll, same `@Scheduled(fixedDelay=15_000)` pattern
already used for `SmsReplyFlowScheduler`.

**Query shape** (run every 15s):
```sql
SELECT c.* FROM marketing.contacts c
WHERE c.created_at <= now() - interval '2 minutes'
  AND c.created_at >= now() - interval '10 minutes'  -- bound the scan window
  AND NOT EXISTS (SELECT 1 FROM lead_followup_send lfs WHERE lfs.contact_id = c.id)
```
The 10-minute upper bound keeps the scan cheap (indexed on `created_at`) without needing a separate
"already looked at this one" cursor table beyond `lead_followup_send` itself, which doubles as both
the idempotency marker and the outcome log in one row per contact — no `AWAITING_SEND` phase is
needed here (unlike `sms_reply_flow`), because there's no reply to wait for after the send; the
row is written with its final state (`SENT` or `SKIPPED_BOOKED`/`SKIPPED_DISABLED`) the moment it's
processed. Simpler than the checkout-review automation's two-phase design because this automation
is genuinely one-way — send once, done.

**How exact is "2 minutes," really?** `@Scheduled(fixedDelay = 15_000)` schedules the *next* run
15 seconds after the *previous* run's own completion — it is not a fixed-rate clock ticking every
15s on the wall regardless of how long each run takes. In practice, given how cheap this poll is
expected to be (see Risks below), each run finishes in low tens of milliseconds, so the effective
cadence is indistinguishable from "every 15s" for this automation's purposes. That gives:

- A contact becomes *eligible* the instant it turns 2:00 old.
- It gets *picked up* on the next poll tick after that — up to ~15s later.
- So the real send window is **2:00–2:15** after the contact was captured, not exactly 2:00:00.
  This is the same precision `SmsReplyFlowScheduler` already runs at for its own delayed sends —
  nothing new is being introduced here.
- Adding the live Square lookup from D2 makes each per-contact iteration of the loop do a real
  network call before deciding to send. That doesn't move the window's start (2:00 is still 2:00),
  but on a poll cycle with several eligible contacts, or if Square's API is briefly slow, the tail
  end of that ~15s window can stretch a bit further before every contact in the batch is handled —
  still on the order of seconds, not minutes, under normal conditions.
- If the backend restarts mid-window (deploy, crash), a contact simply waits for the next poll
  after the process comes back — the row isn't written until it's actually processed, so nothing
  is silently dropped, only delayed. Same durability story as every other `@Scheduled` job in this
  codebase.

Bottom line: "2 minutes" is a floor, not a stopwatch-precise instant — expect **2:00 to roughly
2:15–2:20** in the normal case. If that's not tight enough, the fix would be shortening
`fixedDelay` (e.g. to 5s), which is a one-line change if it ever matters in practice — no need to
decide that now.

### D2: "Has this lead booked" = a live Square check for *any* upcoming appointment, not just this session's

**Decision (revised — see below)**: the first draft of this decision checked only
`marketing.contacts.square_booking_id IS NOT NULL` — i.e. "did *this specific website session* end
in a completed booking." That's insufficient: a customer who already has a future appointment
booked through a completely different channel (a phone call, a walk-in scheduled by staff, mani,
or simply an existing booking from before this contact row was even created) but who casually
re-submits the contact form — checking hours, availability, anything short of actually booking
again — would still get the "need help booking?" text, which is wrong; they don't need help, they
already have an appointment.

Instead, at the 2-minute mark, resolve the contact's Square customer and check **live** for any
appointment that hasn't happened yet and hasn't been cancelled/declined/no-showed — reusing
building blocks `MarketingContactsService`/`MarketingAnalyticsService` already established for
exactly this "does this phone number have a real upcoming appointment" question:

1. Resolve a Square `customerId` for the contact's phone number: use `contact.squareCustomerId()`
   if the tracked flow already set it; otherwise call `SquareClient.customerIdsForPhone(phone)`
   live (the same fallback `MarketingContactsService.syncSquareLinks` already uses for exactly this
   "never went through the tracked flow" case).
2. If a customer resolves, call `SquareClient.bookingsForCustomer(customerId, since = now())`.
3. "Has an upcoming appointment" = any returned booking where `didHappen(booking)` (excludes
   `CANCELLED_BY_CUSTOMER`/`CANCELLED_BY_SELLER`/`DECLINED`/`NO_SHOW`, same helper
   `MarketingAnalyticsService` already uses) **and** `startAt` is still in the future.
4. No customer resolves, or resolves but has no such booking → send. Otherwise → `SKIPPED_BOOKED`.

**Trade-off accepted**: this makes the automation do a live Square call per eligible contact at
send-time (through the same `throttled()`-gated `SquareClient`, so it's rate-limit-safe, but it's
real network latency in the scheduler's loop where the original design was DB-only) — see the
timing note under Risks below for what this does to the "2 minutes" guarantee.

### D3: New table `lead_followup_send`, not a `marketing.contacts` column

```sql
CREATE TABLE lead_followup_send (
    id          BIGSERIAL PRIMARY KEY,
    contact_id  UUID        NOT NULL REFERENCES marketing.contacts(id),
    phone_number TEXT       NOT NULL,
    state       TEXT        NOT NULL CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ON lead_followup_send (contact_id);
```

**Rationale**: `marketing.contacts` is written directly by akluxnails-home (and mani) via raw SQL —
adding a salaryReview-owned column to a table another service writes to raises the odds of a future
schema drift surprise. A new table, owned entirely by salaryReview (like `sms_reply_flow`), keeps
this automation's state isolated from the shared marketing schema while still joining against it
read-only for the trigger query.

### D4: Message copy is transactional (no incentive) — confirmed with the owner

**Decision**: *"Hi {name}, just checking in — need help finding a time that works? Reply here and
we'll help you book! 💅 — AK.LUX.NAILS"* (name-less fallback: *"Hi, just checking in — ..."*). No
discount, no "book now and save," no expiring offer — under the standing SMS compliance rule
([[sms_compliance_rule]] memory), this is transactional and can be sent to any contact with a phone
number, regardless of `sms_marketing_consent`. The owner was explicitly offered the option to
include an incentive (which would require gating on marketing consent, excluding most fresh leads
who haven't had a chance to opt in) and chose to keep it purely helpful.

### D5: Ships disabled by default — same rule as every automation so far

**Decision**: `lead_follow_up` seeds into `sms_automation` with `enabled = false`, per the
established, repeatedly-confirmed rule (see `sms-automations-hub` design.md D8) — the owner turns
it on from `/owner/automations` once satisfied it's been tested.

### D6: MANAGER gets read + reply access, not automation on/off control

**Decision**: extend `SecurityConfig` so MANAGER can hit the activity-read endpoints
(`GET /api/owner/automations/activity`, the new per-contact thread endpoint, mark-read, and the new
manual-reply-send endpoint) — but `PUT /api/owner/automations/{key}` (the enable/disable toggle)
stays OWNER-only. This matches the owner's own framing: reading/replying to customers is day-to-day
work; turning an automation on or off is a business decision. (Confirmed with the owner directly —
this was the explicit assumption stated and accepted before drafting this proposal.)

### D7: New shared page lives at `/admin/messages`, not `/owner/automations`

**Decision**: this app's existing convention is that pages both OWNER and MANAGER use live under
`/admin/*` (e.g. `/admin/redos`, `/admin/manual-adjustments`), while `/owner/*` is OWNER-exclusive
(`/owner/overview`, `/owner/marketing`). `/owner/automations` (registry + full activity + toggles)
stays as-is, OWNER-only. A new `/admin/messages` page is added for the conversation-thread view +
reply composer, visible to both roles, reusing the same `sms_message` data but presented as
per-customer threads instead of a flat filterable table.

### D8: Conversation grouping — one thread per phone number

**Decision**: the new page groups `sms_message` rows by `phone_number` (already the natural key —
every automation, inbound reply, and now manual send is keyed by phone), showing a contact list
(sorted by most-recent-message-first, unread count per contact) and a selected thread (all messages
for that phone number, chronological, sent vs. received visually distinct — same visual language
as a normal texting app). This is a presentation change only; no new grouping table is needed, since
`phone_number` already ties every row together.

### D9: Manual reply is a new endpoint, bypassing templates and automation gating entirely

**Decision**: `POST /api/owner/automations/activity/reply` (or similar), body
`{ phoneNumber, body }`, MANAGER+OWNER. Sends via `TwilioSmsClient` directly (not
`TwilioSmsService.sendTemplated`, since there's no template — this is freeform text a human typed),
logs via `SmsMessageLogService.logOutbound` with `automationKey = null`, `templateKey = null`. No
consent gate: a manager replying to a customer who just texted the salon is a direct conversational
reply, not a marketing send, so it's transactional by the same standing rule regardless of content
(the manager is trusted not to insert discount language here — this is a person-to-person reply, not
an automated campaign).

## Risks / Open Questions

- **Poll query cost**: `marketing.contacts` doesn't currently have traffic volume that would stress
  a 15s poll (67 leads/month at time of writing), but the query should still use the existing
  `idx_marketing_contacts_created_at` index — confirm during implementation that the `NOT EXISTS`
  join against `lead_followup_send` (small, new table) doesn't force a sequential scan at current
  or 10x volume.
- **No per-staff attribution on replies (see proposal.md Non-goals)**: if this becomes a pain point
  once managers actually use it, revisit — would need either a manager-identity column on
  `sms_message` or a session-derived `sent_by` field, deferred for now.
- **Mailchimp/email consolidation** (owner mentioned as a future direction): nothing in this design
  should make adding an email channel harder later, but no design decision here specifically
  prepares for it either — revisit when that work is actually scoped.
