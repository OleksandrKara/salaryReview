-- Lead-follow-up used to send at most once, ever, per marketing.contacts row (unique on
-- contact_id alone) — a lead who left contact info again days later, with no upcoming
-- appointment, silently never got a second nudge. contact_updated_at records which "touch" of
-- the contact row this send/skip corresponds to, so the poll (see
-- MarketingContactsRepository#findPendingFollowUp) can tell "already handled this exact
-- resubmission" apart from "resubmitted again since — worth another nudge."
--
-- Backfilled with created_at for existing rows: a send always happens shortly after the contact
-- touch that triggered it, so created_at is a safe (if not byte-exact) stand-in — it can only
-- ever be later than the true historical contact.updated_at, never earlier, so no existing
-- already-sent lead becomes wrongly eligible for a second send it hasn't actually earned yet.
ALTER TABLE lead_followup_send ADD COLUMN contact_updated_at TIMESTAMPTZ;
UPDATE lead_followup_send SET contact_updated_at = created_at WHERE contact_updated_at IS NULL;
ALTER TABLE lead_followup_send ALTER COLUMN contact_updated_at SET NOT NULL;

DROP INDEX lead_followup_send_contact_idx;
CREATE UNIQUE INDEX lead_followup_send_contact_touch_idx ON lead_followup_send (contact_id, contact_updated_at);
