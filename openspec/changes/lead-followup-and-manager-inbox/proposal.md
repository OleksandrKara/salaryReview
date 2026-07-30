## Why

Two gaps surfaced from real usage of `sms-automations-hub` (the checkout-review-request automation
and the `/owner/automations` hub):

1. **Leads go cold with nobody following up.** A real-data check of `marketing.submissions`
   (2026-07-30, ~1 month of akluxnails-home traffic) found 67 unique leads left contact info via
   the homepage booking flow; only 36 (54%) completed a booking. Of those 36, the vast majority
   converted in the same sitting (median 51 seconds, 90th percentile 7.5 minutes) — there is no
   "typical" multi-hour delay before someone books if they're going to. The other 31 leads simply
   never came back on their own. The owner's read on this: some of those 31 didn't find a slot that
   worked, got confused by a step, or had a question they didn't want to type into a form — and
   with nobody following up, they may go book at a competing salon instead. The owner explicitly
   wants to move fast here (2 minutes after contact capture, not hours), prioritizing not losing a
   customer to a competitor over the small risk of texting someone who was already about to finish
   booking on their own (measured: ~28% of true converters hadn't submitted yet at the 2-minute
   mark — an acceptable, non-harmful overlap given the stated priority).
2. **Managers have no way to see or act on inbound texts.** Today, only the OWNER role can see
   `/owner/automations`'s activity log at all (see `sms-automations-hub` design.md D9, which
   explicitly deferred MANAGER access "but not in this change"). A customer replying to the
   checkout-review automation, or to any future automation, is invisible to managers unless the
   owner happens to check the dashboard. The owner wants managers alerted immediately (Telegram,
   same channel they already watch for 4-hand-request alerts — shipped separately, see
   `telegram-inbound-sms-alert`) **and** able to open their own account, see the full back-and-forth
   with that specific customer, and reply — the way any normal two-way texting app works, not a
   flat activity log they have to filter by hand.

Both gaps are about the same underlying thing: an automated or manual text this business sends
should never be a dead end nobody's watching.

**Explicitly out of scope for this change** (owner confirmed, see conversation): the salon also
sends texts today via Square's own messaging feature and sometimes via its Ooma phone line — none
of that history lives in this system and there's no integration to pull it in. The owner's stated
long-term goal is consolidating everything onto the one Twilio number this platform already
automates on top of, but that's a separate future initiative; this change only touches
salaryReview's own Twilio-number conversations. Likewise, call-forwarding the Twilio number to the
salon's Ooma line (+16193231185) is a one-time Twilio Console configuration, not app code — tracked
separately as an external setup step (see tasks.md section 9), not part of this proposal's design.

## What Changes

- **New automation: `lead_follow_up`.** 2 minutes after a contact leaves their info via the
  akluxnails-home booking flow (`marketing.contacts` row created), if that phone number still has
  no linked Square booking, send one purely-helpful, no-incentive text offering to help them find a
  time — classified **transactional** under the standing SMS compliance rule (no discount/promo
  language), so it can go to any contact with a phone number regardless of marketing-SMS consent.
  Ships **disabled by default**, same safety rule as every automation before it.
- **MANAGER access to a real two-way conversation view.** A new shared page (`/admin/messages`,
  matching this app's existing convention that owner+manager-shared pages live under `/admin/*`,
  not `/owner/*`) grouped **by customer** (one thread per phone number, not a flat log you filter),
  showing every message — automated or manual — in that thread, with a reply box. Both OWNER and
  MANAGER can read and reply; only OWNER keeps the automation on/off toggles (unchanged, still at
  `/owner/automations`).
- **Manual (ad-hoc) reply sending.** A new backend endpoint lets a manager or owner type and send a
  freeform SMS to a specific customer directly from that conversation thread — not template-driven,
  not gated by an automation key, logged into the same `sms_message` activity log as everything
  else so it shows up in both places.

## Non-goals

- Pulling in Square- or Ooma-sent message history — no integration exists for either, and building
  one is a separate, larger consolidation project the owner wants to do later, not now.
- Automating a reply to the phone-call-forwarding request — that's a one-time Twilio Console change
  (call the toll-free number → ring the Ooma line), tracked as an external step, not app code.
- Changing anything about the checkout-review-request automation's own reply-branching logic — the
  manager conversation view is additive visibility/reply on top of the existing activity log, not a
  rework of that automation.
- A per-manager identity on sent messages (e.g., "Maria replied") — out of scope for v1; every
  manual reply logs the same way an automation's reply does, distinguishable by `automation_key IS
  NULL` + a manual-send marker, not by which staff member sent it.
