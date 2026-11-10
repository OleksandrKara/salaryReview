ALTER TABLE timecard_review DROP CONSTRAINT IF EXISTS timecard_review_status_check;
ALTER TABLE timecard_review ADD CONSTRAINT timecard_review_status_check CHECK (status IN
    ('PROPOSED', 'WORKER_CONFIRMED', 'NEEDS_REVIEW', 'APPROVED', 'CONFIRMED_PENDING_WRITE', 'VOIDED'));
ALTER TABLE timecard_review ADD COLUMN voided_at TIMESTAMPTZ;
ALTER TABLE timecard_review ADD COLUMN voided_by TEXT;
ALTER TABLE timecard_review ADD COLUMN void_reason TEXT;
