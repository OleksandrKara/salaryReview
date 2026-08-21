-- Quick manager log: "a customer wanted an appointment on this date/time and we had nowhere to
-- put them, everything was booked" — with an estimated dollar amount of the revenue that missed
-- booking cost. Deliberately its own table, not a note on an existing entity: there's no real
-- Square booking/order behind a slot that was never actually offered, so nothing else in this
-- schema has anywhere to hang this fact. Meant to build up a dataset the owner can later mine
-- (by month, by day of week, by requested time) to judge whether demand is consistently
-- outrunning capacity enough to justify hiring another provider.
CREATE TABLE missed_booking (
    id                 BIGSERIAL PRIMARY KEY,
    business_id        BIGINT NOT NULL REFERENCES business(id),
    requested_date     DATE NOT NULL,
    requested_time     TIME,
    estimated_revenue  NUMERIC(10,2) NOT NULL,
    service_name       TEXT,
    created_by         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_missed_booking_business_date ON missed_booking (business_id, requested_date DESC);
