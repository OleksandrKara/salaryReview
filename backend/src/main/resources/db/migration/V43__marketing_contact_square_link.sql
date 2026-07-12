-- Owned by this app (not the salonLandings marketing schema): caches a phone-number → Square
-- customer id resolution for a marketing.contacts lead that never completed the tracked booking
-- flow (a manager followed up and booked them by phone, or they came back through some other
-- channel entirely), so the Contacts tab can show that lead's real appointment/no-show/cancelled
-- history once "Sync appointments" has found the match — without ever writing to salonLandings'
-- own marketing.contacts table.
CREATE TABLE marketing_contact_square_link (
    id                 BIGSERIAL PRIMARY KEY,
    phone_number       VARCHAR(32) NOT NULL,
    square_customer_id VARCHAR(64) NOT NULL,
    last_synced_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_contact_square_link_phone UNIQUE (phone_number)
);
