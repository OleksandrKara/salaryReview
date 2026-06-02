-- No-show fee tracking — overrides only. No-shows and their paid $25 "Cancelation Policy" fees are
-- derived live from Square (read-only); this table records only the owner/manager exceptions:
--   SUPPRESS — do NOT credit an auto-detected fee (false positive / disputed).
--   CONFIRM  — credit the provider $25 for a fee collected off-signal (cash / quick-sale, odd label,
--              or paid more than 2 months after the no-show). Self-contained (provider, customer,
--              dates, amount) so it needs no Square lookup to apply.
CREATE TABLE no_show_fee_override (
    id                BIGSERIAL PRIMARY KEY,
    square_booking_id VARCHAR(255) NOT NULL UNIQUE,
    kind              VARCHAR(16) NOT NULL CHECK (kind IN ('CONFIRM', 'SUPPRESS')),
    provider_id       BIGINT REFERENCES providers(id),   -- CONFIRM: who to credit
    customer_name     VARCHAR(255),                       -- CONFIRM: for display
    no_show_date      DATE,                               -- CONFIRM: for display
    amount            NUMERIC(10,2) NOT NULL DEFAULT 25,
    fee_paid_date     DATE,                               -- CONFIRM: the month the credit lands in
    note              VARCHAR(255),
    created_by        VARCHAR(100),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
