## Context

`sms-automation-platform` (shipped) established: salaryReview owns all Twilio credentials and
sending; every template has a server-side-fixed `messageClass` (`TRANSACTIONAL`/`MARKETING`); mani
and akluxnails-home relay through `POST /api/internal/notifications/sms/send`. One template exists
today, `four_hand_request_received` (`TRANSACTIONAL`), triggered synchronously from each booking
app's 4-hand submit handler.

Since then, in this same session: the Toll-Free Verification for `+18449814613` was fixed and
approved (two rejection rounds — mixed use-case consent, then a pre-selected-checkbox screenshot
that turned out to be a CDN/`Cache-Control: immutable` staleness bug, not a real re-submission
issue) and confirmed live with a real test send. The owner separately pursued A2P 10DLC
registration for the same number, hit the identical "mixed use case" rejection on the Campaign, and
— now that Toll-Free Verification covers real sending needs — explicitly dropped that path rather
than fix it. Every send in this change rides the approved Toll-Free number exactly like the 4-hand
confirmation already does.

Confirmed by direct code search for this change: **`SquareClient`'s `Order` record has no `source`
or fulfillment/booking-linkage field today** (it was built for payroll/revenue aggregation, which
never needed to distinguish an online-booking payment from an in-salon POS sale), and **`Customer`
has no phone number field** (existing phone lookups only go phone→customer-ids, via
`customerIdsForPhone`, never the reverse). Both need extending. Confirmed again: **no app in this
stack has a public inbound webhook endpoint** (Square or Twilio) and **no delayed/scheduled-send
mechanism exists** — the only recurring jobs are `RevenueSnapshotScheduler` (01:30 salon-local) and
`ProviderVisitScheduler` (02:00 salon-local), both whole-month re-aggregations that fire once a
day, nowhere near responsive enough for a 2-minute delay window.

## Goals / Non-Goals

**Goals:**
- Make every SMS automation — existing and new — visible, toggleable, and logged from one owner
  UI, without inventing a full campaign-builder.
- Detect an in-salon checkout within roughly a minute (a 2-minute total delay budget doesn't
  tolerate a slow poll on top of it).
- Receive and correctly attribute an SMS reply to the specific pending flow that's awaiting it.
- Make the review-link click-through actually measurable.
- Keep the same server-side, caller-can't-override compliance shape `sms-automation-platform`
  already established — this change extends that gate, it doesn't bypass or duplicate it.
- Give an inbound message that doesn't match any automation the same "needs attention" visibility
  an unread email would get — a customer can text this number anything, at any time.
- Make it structurally impossible for a newly-added automation to fire before the owner has
  explicitly turned it on.
- The hub itself has to be genuinely pleasant to use from a phone, not just functional there.

**Non-Goals:**
- A generic workflow/automation-builder UI — see proposal.md Non-goals.
- Any coupon/expiring-discount mechanism — deferred, and would need its own `MARKETING`-class
  consent design if built later.
- Reconciling missed Square webhook deliveries beyond Square's own retry window (see Risks).
- MANAGER-role access to the inbox/activity log — owner-only in this change; a limited manager view
  is a deliberate later follow-up (see D9).

## Decisions

### D1: Square webhook receiver, not a tighter poll, for checkout detection

**Decision**: Subscribe to Square's `payment.updated` webhook event and react to `COMPLETED`
payments, rather than adding a faster poll.

**Rationale**: `sms-automation-platform` design.md D5 already weighed this exact trade-off for this
exact use case and recommended the webhook, given the time-sensitivity — that reasoning hasn't
changed, and now there's an even tighter total budget (2 minutes end-to-end) than the original
sketch's "3 minutes, then a 2h-expiring coupon." A 1–2 minute poll would eat a meaningful fraction
of that budget just on latency, adds recurring Square API load, and still isn't as tight as a
webhook. This is the first Square webhook this stack has ever needed — signature verification
(Square signs with `x-square-hmacsha256-signature` = `HMAC-SHA256(notificationUrl + rawBody,
signatureKey)`, base64-encoded) is new code, timing-safe-compared the same way
`InternalNotificationController.keyMatches` already compares the internal API key
(`MessageDigest.isEqual`).

**Alternatives considered**: a 1-minute poll against `completedOrders` — rejected per the above;
would also require guessing at "new since last poll" cursoring logic that a webhook makes
unnecessary (Square tells you exactly when a payment completes).

### D2: "In-salon" means a completed Order with no linked booking, resolved via the payment's Order

**Decision**: On a `payment.updated` webhook with `status: COMPLETED`, fetch the payment's
`order_id`, then the Order. If that Order has no linked `booking_id` (a new field to add to
`SquareClient.Order` — Square's Orders API exposes this via the order's `fulfillments`/reference,
confirm exact field name against a real sandbox order before implementing), treat it as an in-salon
POS sale; if it *is* linked to an online booking, skip — that flow already has its own touchpoints
and isn't what "the machine at checkout" refers to. No tender-type filter (cash and card both
qualify) — the owner's "often by card" was descriptive, not a stated restriction; if that turns out
wrong in practice it's a one-line filter to add.

**Then**: resolve the order's `customer_id` to a phone number. `SquareClient.Customer` needs a
`phoneNumber` field (a new dedicated `GET /v2/customers/{id}` call, or extended into the existing
customer-lookup methods — implementation detail for tasks.md). **If Square has no phone on file for
that customer** (a genuinely anonymous walk-in with no profile), the flow silently doesn't enqueue
anything — same "never block, never throw" shape as every other notifier in this codebase.

**Rationale**: this reuses exactly the signal that's actually available (the payment/order graph)
rather than inventing a new "was this from the Terminal app" heuristic; Square's own Orders API
already carries the online-vs-in-person distinction because bookings feed the order via a
different path than a POS-created order.

**Confirmed, not a gap**: salaryReview's own Manual Adjustment feature (`ManualAdjustmentService`,
the renamed former "Manual Credits") was raised during review as a possible false trigger — a
cash-note correction entered by the owner/a manager for some dollar amount. Confirmed by direct
code search: that service only ever writes to salaryReview's own Postgres tables and never calls
Square (this codebase's Square access is strictly read-only everywhere). A Manual Adjustment
therefore never produces a Square `payment.updated` event and structurally cannot enqueue this
automation — no additional filter is needed for it.

### D3: A durable, DB-backed delayed-send job — not an in-memory timer

**Decision**: New `sms_reply_flow` table, one row per in-flight checkout-review conversation:
`id`, `automation_key`, `phone_number`, `customer_name` (nullable), `state`
(`AWAITING_SEND` → `AWAITING_REPLY` → `COMPLETED` | `EXPIRED`), `send_due_at`, `reply_expires_at`,
`created_at`, `updated_at`. A new lightweight `@Scheduled` poller (every ~15s, matching the
granularity a 2-minute window needs) picks up `AWAITING_SEND` rows whose `send_due_at` has passed,
sends the rating SMS, flips to `AWAITING_REPLY` with `reply_expires_at = now() + 24h`. A separate
sweep (or the same poller) flips any `AWAITING_REPLY` row past its `reply_expires_at` to `EXPIRED`.

**Rationale**: an in-memory `ScheduledExecutorService`-style delay would silently lose every
pending job on a backend restart/redeploy (this app redeploys routinely, same-day, per this
session's own history) — a DB row surviving a restart is the same reasoning this codebase already
applies everywhere else state needs to survive a deploy (e.g. `RevenueSnapshotScheduler`'s
DB-backed snapshots, not an in-process cache). A 15s poll interval is cheap (single indexed query)
and imperceptible against a 2-minute target.

**`checkout_review_request` is seeded `enabled = false`** in the `V52` migration — see D8's "ships
disabled" rule. The scheduler and webhook receiver are fully wired and testable the moment this
change deploys, but no real customer gets a real text until the owner flips it on from the hub
after end-to-end testing. `four_hand_request` is seeded `enabled = true` (it's already live and
owner-tested in production; this migration doesn't change its behavior, only makes it visible).

### D4: Twilio inbound webhook matches the most recent `AWAITING_REPLY` row for that phone number

**Decision**: New `POST /api/public/sms/inbound` (Twilio-signature-verified via
`X-Twilio-Signature`: HMAC-SHA1 of the full webhook URL + sorted POST params, using the account's
Auth Token — Twilio's documented validation scheme). On a valid inbound message: log it to
`sms_message` (`direction = INBOUND`) unconditionally, then look up the newest `AWAITING_REPLY`
`sms_reply_flow` row for the sender's normalized phone number. If found: parse the body for the
digit `5` anywhere in it (`contains("5")`, tolerant of "5", "5!", "Five" spelled out is *not*
matched — digits only, matching the literal "reply with a number" instruction); branch to the
Google review short link or the feedback-form short link accordingly; flip the row to `COMPLETED`.
If no matching row (an unsolicited text, a reply after 24h expiry, or a reply to something that was
never sent by us): log it, no automated reply — Twilio/carriers already intercept and handle
`STOP`/`HELP`/`START` keywords before they'd ever reach this endpoint, so this path only ever sees
genuine free-text replies.

**Rationale**: matching by "newest pending row for this phone number" is correct at this salon's
real scale (one in-flight review conversation per phone number at a time in the overwhelming
common case); no message-thread/session-id scheme is needed. Always logging the inbound message
first, independent of whether it matches a flow, is what makes the "what SMS did we receive"
activity view in the hub complete rather than only showing replies we happened to act on.

### D5: The whole checkout→rating→review flow is `TRANSACTIONAL`

**Decision**: The rating-request SMS and both branch replies (Google review link / feedback-form
link) are registered as `TRANSACTIONAL` — they fire for every in-salon paying customer, not gated
by `sms_marketing_consent`.

**Rationale** (owner-confirmed this round): none of the three messages contain a discount, coupon,
or sales call-to-action — they're a direct, one-time follow-up to a transaction the customer just
completed, structurally the same category as the already-shipped 4-hand confirmation (which is
also a direct-response confirmation of the customer's own just-completed action, not a promotion).
Gating this behind the marketing checkbox would mean many walk-in card customers — who may never
have seen or checked that box — get no review ask at all, which defeats the point. This does **not**
reopen or contradict the Toll-Free Verification's approved `MARKETING`-only use-case category: that
approval governs *promotional/discount* content specifically (see the toll-free rejection history
in `sms-automation-platform`'s successor conversation), and a content-neutral, non-promotional
follow-up tied to a same-day transaction is the same category of exception the 4-hand confirmation
already relies on today without incident.

### D6: Click-tracked short links are keyed by the outbound `sms_message` row, not a separate table

**Decision**: `GET /r/{token}` (public, no auth) where `{token}` is a fresh, random 8-character
lowercase-alphanumeric (base36) opaque token (see `ClickTokens`), stored on
`sms_message.click_token` (unique) — not the row's own sequential `id`. An earlier revision used
the raw numeric `id` directly on the reasoning that a guessable id leaks nothing security-relevant
here (only two fixed destinations exist either way). That's still true, but the owner flagged
during a real test send that a bare incrementing number at the end of the link (e.g.
`.../r/1234`) *reads* like a raw tracking-link artifact rather than a normal business link, which
hurts click-through trust — so this was revised to an opaque token purely for that perception
reason, not a security one. The token is deliberately single-case (not mixed-case) and kept short
(8 chars, ~2.8 trillion combinations — far more than this business will ever send) to match the
visual convention of trusted link-shorteners (bit.ly, tinyurl) rather than looking like a random
tracking blob; SMS-segment cost was *not* the driver here (the owner explicitly deprioritized that
given current low message volume — see the emoji/em-dash note below, unchanged for now for the
same reason). That row also gains `link_target` (`GOOGLE_REVIEW` | `FEEDBACK_FORM`) and
`clicked_at` (nullable). The redirect handler resolves the real destination from `link_target`
(the two real URLs — Google Maps review page and the feedback Google Form — are small, fixed,
code-level config, not owner-editable copy, matching every other "no CMS" convention here), stamps
`clicked_at` if unset, and issues a `302`.

**Known lever not yet taken**: the templates' emoji (💅🌟) and em-dash (—) force UCS-2 SMS encoding
(70-char segments) instead of GSM-7 (160-char segments), roughly doubling per-message Twilio cost
regardless of link length — confirmed by direct measurement when this was investigated. The owner
was offered this fix and declined it for now (current volume is low enough that it doesn't matter
yet) — revisit if/when send volume grows enough for segment cost to matter.

**Rationale**: reusing the message-log row instead of a new join table means "was this specific
text ever clicked" is answerable directly from the same activity log the hub UI already renders —
one row, one place, no extra join for the common case of "show me this message and whether it was
opened."

### D7: Registry + activity log live under `/owner/automations`, not folded into `/owner/settings`

**Decision**: New top-level nav entry and page, not a third settings sub-page. Settings pages
(`/owner/settings/telegram`, `/owner/settings/sms`) are where *credentials* live — this is where
the owner *watches what's happening*, which the owner explicitly asked to be visible, not tucked
under configuration. Reuses `PageHeader`, the Contacts tab's list/filter visual language, and the
existing settings-page toggle control style for the enable/disable switch — no new visual language
invented.

**Rationale**: matches the distinction already implicit in this app's IA between "how do I
configure this integration" (Settings) and "what is this integration actually doing" (a dashboard
tab, like Ads Report/Funnel/Contacts already are for marketing data).

### D8: Every newly-added automation is seeded disabled — enabling is always a separate, explicit act

**Decision**: `sms_automation.enabled` defaults to `false` at the database level
(`DEFAULT false`), and the `V52` migration explicitly seeds `checkout_review_request` as `false`.
The only automation seeded `true` is `four_hand_request`, because it is already live in production
today — this migration doesn't turn anything on, it makes an already-running thing visible. Any
*future* automation this hub gains after this change inherits the same `false` default unless a
migration explicitly overrides it, and doing that should require the same owner sign-off this
change's own review process just went through.

**Rationale** (owner-requested this round): a new automation must never be able to start
texting real customers as a side effect of merging/deploying code — enabling it has to be a
distinct, visible, owner-initiated action from the hub, after the owner has had a chance to test
it. Defaulting the column itself to `false` (not just the one seed row) means this protection
survives even if a future automation's migration forgets to set the value explicitly.

### D9: Inbound messages carry real read/unread state; MANAGER access is explicitly deferred

**Decision**: `sms_message` gains `read_at` (nullable timestamptz, inbound-only — always `NULL` for
`OUTBOUND` rows). The hub's inbox view shows an unread count (rows with `direction = INBOUND` and
`read_at IS NULL`) prominently, independent of whether that message ever matched an
`sms_reply_flow`. Opening/viewing an inbound message (or an explicit "mark read" action) stamps
`read_at`. `GET /api/owner/automations/activity` and the mark-read endpoint are OWNER-only in this
change — no MANAGER role is granted access here.

**Rationale**: the owner specifically wants a scenario where a customer texts this number
something unprompted — not a reply to an automation, not something Twilio/carriers intercept
(STOP/HELP) — to be impossible to miss, the same way an unread email demands attention. Because
D4 already logs every inbound message unconditionally (matched or not), this is additive: one
column, one filter, no change to the matching logic itself. MANAGER access is explicitly future
work, not scoped here — when it's built, it's expected to need its own "limited" shape (the owner
said "with certain limits," not full parity with owner access), which is real design work of its
own and shouldn't be guessed at now.

### D10: The hub is designed mobile-first, not "responsive as an afterthought"

**Decision**: `/owner/automations` and its activity/inbox view follow the same mobile-card /
desktop-table split already established in this app's marketing dashboard (e.g. `LtvView`'s
`sm:hidden` card list + `hidden sm:block` table, `ContactsFilterBar`'s mobile-first filter layout).
Automation cards stack in a single column on narrow viewports; the enable/disable toggle is a
full-size touch target (matching the existing settings-page toggle's tap area, not shrunk); the
activity/inbox list renders as a card-per-message on mobile (sender, snippet, timestamp, unread
indicator) and a dense table on desktop, not a horizontally-scrolling table on both.

**Rationale**: the owner already checks salaryReview's dashboards from a phone as a matter of
course (this session's own back-and-forth happened largely via mobile-originated messages) — this
isn't a "nice to have," it's the primary way this specific page is likely to get checked
throughout a work day. Reusing the exact split already proven out in `LtvView`/`ContactsFilterBar`
means no new responsive pattern needs inventing or separately reviewing.

## Risks / Trade-offs

- **Square webhook delivery isn't guaranteed within this app's control** — if Square's delivery to
  our endpoint fails and its own retries are exhausted, that one in-salon checkout silently gets no
  review request, with no reconciliation poll built here to catch it (see proposal.md Non-goals).
  Acceptable at this volume (one salon, not thousands of transactions/day) but worth revisiting if
  missed-webhook reports come in.
- **Order→booking-linkage field needs confirming against a real sandbox order** before
  implementation — the exact Square API field name/shape for "this order came from a booking" is
  asserted here from the Orders API's general shape, not yet verified against a live payload; task
  1 in tasks.md is to confirm this before writing the filter logic.
- **A customer who pays in-salon but has no phone on file in Square** gets no review request at
  all — same silent-skip shape as every other notifier here, but worth the owner knowing this isn't
  a 100%-coverage automation.
- **The "contains digit 5" reply parse is intentionally narrow** — a reply like "no, more like a 3"
  or "it was ok" won't be read as a 5 (correct), but a reply like "not a 5 out of 5, more like a 2"
  *would* false-positive on the review link (the literal substring "5" appears). Flagged, not solved
  — the owner's own instruction was "if the reply contains 5," and real-world reply text at this
  volume is expected to mostly be a bare digit given the SMS explicitly asks for one.

## Open Questions

- Should the feedback Google Form response include which customer sent it (e.g. a hidden
  pre-filled field carrying the phone number or an opaque token), so a specific complaint can be
  traced back to a visit? Not decided here — the form URL the owner gave is a bare link; adding a
  prefilled/hidden field is a small follow-up if wanted, does not change this change's scope.
- Whether to also surface a *manual* "send now" action from the hub (e.g. re-fire the 4-hand
  confirmation for a specific phone number without a real request) — not requested, not built here.
- What "limited" MANAGER access to the inbox should mean when it's eventually built (read-only?
  no phone-number search? redacted numbers? no automation toggles regardless?) — explicitly left
  for that future change; this one only guarantees OWNER-only for now doesn't paint that future
  change into a corner.
