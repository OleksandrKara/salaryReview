-- One confirmed draw-down of a prepaid package: an owner/manager confirmed that a real Square
-- booking (service) was performed against the package. The provider is paid on menu_price (gross,
-- like card); counts = whether it clears the tier cutoff. The unique key stops a booked service
-- being redeemed twice (across any package). created/confirmed are an audit trail.
CREATE TABLE prepaid_redemption (
    id                   BIGSERIAL PRIMARY KEY,
    package_id           BIGINT NOT NULL REFERENCES prepaid_package(id) ON DELETE CASCADE,
    square_booking_id    VARCHAR(64) NOT NULL,
    service_variation_id VARCHAR(64) NOT NULL,
    service_name         VARCHAR(255),
    service_date         DATE NOT NULL,
    menu_price           NUMERIC(10,2) NOT NULL,
    counts               BOOLEAN NOT NULL,
    confirmed_by         VARCHAR(100),
    confirmed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (square_booking_id, service_variation_id)
);

CREATE INDEX prepaid_redemption_service_date_idx ON prepaid_redemption (service_date);
