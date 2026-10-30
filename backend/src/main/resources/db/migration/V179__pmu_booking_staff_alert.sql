-- Staff-chat alert for PMU procedures booked through the website (Square source API).
-- One row per Square booking id: durable dedup across redeploys and the automation "sent" count.
CREATE TABLE pmu_booking_staff_alert (
    id                BIGSERIAL PRIMARY KEY,
    business_id       BIGINT NOT NULL,
    square_booking_id VARCHAR(64) NOT NULL,
    service_names     TEXT,
    delivery_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at      TIMESTAMPTZ,
    UNIQUE (business_id, square_booking_id)
);

-- Owner asked for this alert in the team chat (2026-10-30), so on for the PMU business (id 2).
INSERT INTO sms_automation (business_id, automation_key, enabled, updated_by)
SELECT 2, 'pmu_booking_staff_alert', true, 'migration V179'
WHERE EXISTS (SELECT 1 FROM business WHERE id = 2)
ON CONFLICT DO NOTHING;
