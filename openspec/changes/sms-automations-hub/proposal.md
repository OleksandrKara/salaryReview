## Why

`sms-automation-platform` shipped the sending path, the compliance gate, and exactly one live
automation (the 4-hand-request confirmation) — deliberately deferring everything else, including
any owner-facing visibility into what SMS automations exist at all. Today the owner has no way to
see, from the app, that the 4-hand confirmation is even running, whether it's on, who it applies
to, or what's actually been sent to (or received from) a real phone number. Every SMS automation
so far is invisible unless someone reads the code.

The Toll-Free Verification is now approved (`+18449814613`, marketing-only use case, live — see
persisted memory `twilio_tollfree_approved`), and the owner has explicitly dropped the parallel
A2P 10DLC path. That number is the one and only sending identity this change (and everything after
it) builds on.

The owner now wants three concrete things:
1. **Visibility**: an "Automations" area on the dashboard listing every SMS automation, whether
   each is on/off, who it targets, and a full log of every SMS sent and received — including a
   read/unread inbox view of *inbound* messages that's independent of whether they matched any
   automation, since a customer can text this number anything at any time, not just reply to a
   flow. OWNER-only for now; the owner explicitly wants a limited MANAGER view added later, but not
   in this change.
2. **A second live automation**: after an in-salon (Square Terminal/POS) card checkout, wait 2
   minutes, then text the customer a 1–5 satisfaction rating request. A reply containing "5" gets a
   Google review link back; anything else gets a private feedback-form link instead — both routed
   through a salaryReview-hosted short link so click-through is measurable.
3. **Safety by default**: the new automation ships *disabled* — the owner turns it on explicitly
   once it's been tested end-to-end, so it can never start firing on real customers mid-build.

This is a bigger lift than automation #1 was: it needs a way to detect "an in-salon checkout just
happened" within about a minute (nothing in this stack polls faster than once a day today — see
`sms-automation-platform` design.md D5, which flagged exactly this gap and recommended a Square
webhook receiver for it), a way to *receive* an SMS reply (nothing in this stack has any inbound
SMS handling today), and a short delay between two automated messages (nothing in this stack has a
delayed-send mechanism today). All three are genuinely new capabilities, not extensions of
existing plumbing.

## What Changes

- **New "Automations" dashboard page** (`/owner/automations`) — a card per automation showing its
  name, plain-English trigger/audience description, an on/off toggle, and a 30-day sent count.
  Clicking into one shows its own recent activity. Fully responsive — the owner checks this from a
  phone as often as a desktop, so it's designed mobile-first, not "shrunk to fit" (see design.md
  D9).
- **A shared, automation-independent "Inbox/Activity" view** — every SMS ever sent or received
  (direction, phone, automation if any, body, status, timestamp), searchable by phone number.
  Inbound messages carry a genuine read/unread state (not just a log line) — a customer can text
  this number anything, any time, whether or not it matches something we're waiting on, and an
  unread reply needs to visibly demand attention the same way an unread email would (see design.md
  D8). OWNER-only in this change; a limited MANAGER view is explicitly a later follow-up, not built
  here.
- **Automation registry becomes real, not just code** — a new `sms_automation` table backs the
  on/off toggle per automation key; `TwilioSmsService` checks it before sending and now logs
  *every* outbound attempt (sent or not) and every inbound message to a new `sms_message` table,
  regardless of which automation (if any) it belongs to. **Any newly-added automation is seeded
  disabled** — the owner has to explicitly flip it on; nothing new ever goes live silently.
- **The existing 4-hand confirmation becomes the registry's first entry** — same template, same
  `TRANSACTIONAL` class, same trigger point; it just becomes visible/toggleable/logged instead of
  invisible. Since it's already live and owner-tested in production, it's seeded *enabled* (the
  "ships disabled" rule above applies to newly-added automations, not to one that's already
  running correctly).
- **New automation: post-checkout satisfaction request.**
  - Trigger: a new Square webhook receiver reacts to a completed in-salon payment (Square Terminal
    order, i.e. no linked online booking) within seconds — see design.md D1 for why webhook over
    polling, and D2 for exactly how "in-salon" is distinguished from an online-booking payment.
  - 2 minutes later (a new durable delayed-job mechanism — see D3), one SMS: *"Hi {{name}}, on a
    scale of 1 to 5, how did you like your nails today? 💅 Just reply with a number."* —
    **TRANSACTIONAL** (see design.md D5 for why: it's a direct, one-time follow-up to a
    transaction the customer completed minutes earlier, carries no discount/promo content, and
    fires for every in-salon paying customer regardless of marketing opt-in, mirroring the
    4-hand-confirmation precedent).
  - A new Twilio inbound-SMS webhook receives the reply and branches: contains "5" → a
    salaryReview-hosted short link that redirects to the AK.LUX.NAILS Google Maps review page;
    anything else → a short link to the private feedback Google Form. Both short links are logged
    with a `clicked_at` timestamp the moment they're followed.
  - No reply within 24h → the pending flow just expires; nothing further is sent.

## Non-goals

- **No coupon/discount is attached to either branch** — the original roadmap sketch in
  `sms-automation-platform` proposal.md paired this with an expiring 10%-off coupon; the owner
  explicitly wants a plain satisfaction-and-review ask this round. A discount-bearing variant would
  be `MARKETING`-class and is its own future change if wanted.
- **No general-purpose "build your own automation" UI.** Automations stay defined in code (matching
  every other "no CMS yet" convention in this codebase); the new UI makes *existing* automations
  visible/toggleable, it doesn't let the owner author new trigger/message logic from a form.
- **No changes to the two other roadmap items** from the original proposal (abandoned-lead nudge,
  3+ week win-back) — still their own future changes.
- **No retry/backoff on a missed Square webhook delivery beyond Square's own retry policy** — if
  Square's webhook delivery fails outright (rare, and Square itself retries with backoff for a
  while), this change does not add a reconciliation poll to catch missed events. Flagged as a risk
  in design.md, not solved here.
- **Doesn't touch A2P 10DLC** — that path is dropped; every send in this change goes out over the
  approved Toll-Free number the same way the 4-hand confirmation already does.
- **No new filtering for salaryReview's own Manual Adjustment feature** — confirmed by direct code
  search: `ManualAdjustmentService` only ever writes to salaryReview's own database and never calls
  Square (Square access in this codebase is strictly read-only). A manual cash-note/adjustment
  entered by the owner or a manager therefore never produces a Square `payment.updated` event and
  can never enqueue this automation — this was a real question raised during review, confirmed
  safe by construction, not something this change needs to add a guard for.
- **No MANAGER-role access to the inbox/activity log in this change** — the owner explicitly wants
  this to stay owner-only for now, with a limited manager view as a deliberate later addition.

## Capabilities

### New Capabilities

- `sms-automations-hub`: owner-facing registry (enable/disable per automation, audience
  description) + a complete sent/received SMS activity log, backing a new `/owner/automations`
  dashboard page.
- `checkout-review-request-automation`: Square webhook receiver for in-salon checkout completion,
  a durable 2-minute delayed send, a Twilio inbound-SMS webhook, reply branching (rating "5" vs.
  not), and click-tracked short links for the two review destinations.

### Modified Capabilities

- `sms-automation-platform`: `TwilioSmsService.sendTemplated` gains an enable/disable check against
  the new registry and now logs every outbound attempt and every inbound message; the
  `four_hand_request_received` template gets an `automationKey` so it appears in the hub.

## Impact

- **Backend (salaryReview)**: new `V52__sms_automations.sql` migration (`sms_automation`,
  `sms_message` — including `read_at`, `sms_reply_flow` tables — see design.md); new
  `com.salonreview.square.webhook` package (signature-verified Square webhook receiver); new
  `SquareWebhookProperties` (`SQUARE_WEBHOOK_SIGNATURE_KEY`); extends `SquareClient` with a
  customer-phone lookup and an order→booking-linkage check; new `SmsReplyFlowScheduler`
  (`@Scheduled`, polls due delayed sends); new inbound-SMS controller (Twilio-signature-verified,
  `permitAll()`); new `ShortLinkController` (`GET /r/{id}`, public, records a click then `302`s);
  extends `TwilioSmsService`/`SmsTemplateRegistry` with `automationKey` + logging; new
  `SmsAutomationController`/`SmsActivityController` at `/api/owner/automations/**` (OWNER-only,
  including mark-read/unread-count endpoints).
- **Frontend (salaryReview)**: new `/owner/automations` page + automation detail view + inbox/
  activity view, reusing `PageHeader`, the existing mobile-card/desktop-table split already used by
  the marketing dashboard's Contacts and LTV views (see design.md D9), and the existing
  settings-page toggle pattern — built mobile-first given the owner checks this from a phone.
- **Square Developer Dashboard**: new webhook subscription (`payment.updated`), configured once
  against the new receiver URL — a real, external, one-time setup step (see tasks.md).
- **Twilio Console**: the toll-free number's inbound-SMS webhook URL gets set to the new receiver —
  another real, external, one-time setup step.
- **Verification**: unit tests for signature verification (both webhooks reject unsigned/
  mis-signed requests), the reply-branching logic (contains "5" vs. not, case/whitespace-
  tolerant), the delayed-send scheduler (due jobs fire once, not-yet-due jobs don't, expired
  jobs stop awaiting a reply), and the registry enable/disable gate. Manual check once real
  webhooks are wired: one real in-salon test transaction on Square Sandbox/production, confirm the
  rating SMS arrives ~2 minutes later, reply "5" and confirm the Google review link arrives,
  confirm the click is logged.
