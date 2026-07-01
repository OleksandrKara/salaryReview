-- One row per cancelled appointment the owner has reviewed (checked cameras) and decided is fine.
-- Detection happens live each load (see CancelledAppointmentService); this table is the "I looked at
-- this cancellation, no procedure was done" stamp that keeps it off the warning badge. Delete the row
-- to un-clear. Kept separate from suspicious_booking_clearance so the two review flows stay independent.
CREATE TABLE cancellation_clearance (
    id                    BIGSERIAL PRIMARY KEY,
    square_booking_id     VARCHAR(255) NOT NULL UNIQUE,
    cleared_by_username   VARCHAR(100) NOT NULL,
    cleared_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    note                  VARCHAR(255)
);

CREATE INDEX idx_cancellation_clearance_cleared_at
    ON cancellation_clearance (cleared_at DESC);
