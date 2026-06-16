-- One row per booking the owner/manager has reviewed and decided is not suspicious. Detection
-- happens live each load (see SuspiciousBookingService); this table is the "I looked at this,
-- it's fine" stamp that keeps cleared bookings off the badge count. Delete the row to un-clear.
CREATE TABLE suspicious_booking_clearance (
    id                    BIGSERIAL PRIMARY KEY,
    square_booking_id     VARCHAR(255) NOT NULL UNIQUE,
    cleared_by_username   VARCHAR(100) NOT NULL,
    cleared_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    note                  VARCHAR(255)
);

CREATE INDEX idx_suspicious_booking_clearance_cleared_at
    ON suspicious_booking_clearance (cleared_at DESC);
