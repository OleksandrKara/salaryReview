-- One-off re-engagement of clients who had a PMU consultation long ago and never booked (owner
-- approved 2026-10-07, ConsultationReengageOneOffService): each recipient gets a
-- consultation_follow_up row for their last consultation, offer_state SENT, so the existing
-- closeExpiredOffers ends (or extends, for a client who booked) their READY75 group membership
-- after the 7 days exactly like the day-45 offer. stop_reason REENGAGE keeps the regular
-- four-step sequence away from these rows.
ALTER TABLE consultation_follow_up DROP CONSTRAINT consultation_follow_up_stop_reason_check;
ALTER TABLE consultation_follow_up ADD CONSTRAINT consultation_follow_up_stop_reason_check
    CHECK (stop_reason IN ('BOOKED', 'REPLIED', 'STAFF', 'NOT_ELIGIBLE', 'CANCELLED', 'REENGAGE'));
