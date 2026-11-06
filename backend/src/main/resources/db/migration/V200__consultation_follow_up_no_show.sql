-- A consultation the client did not attend must not run the thank-you / info / check-in / offer sequence.
ALTER TABLE consultation_follow_up DROP CONSTRAINT consultation_follow_up_stop_reason_check;
ALTER TABLE consultation_follow_up ADD CONSTRAINT consultation_follow_up_stop_reason_check
    CHECK (stop_reason IN ('BOOKED', 'REPLIED', 'STAFF', 'NOT_ELIGIBLE', 'CANCELLED', 'REENGAGE', 'NO_SHOW'));
