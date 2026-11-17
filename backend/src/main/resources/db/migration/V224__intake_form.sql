-- First-visit nail intake form (owner request 2026-11-17): every first-time AK.LUX.NAILS client gets
-- a short health/preferences form by SMS and signs it on their phone; staff see status and answers
-- in the CRM. One row per customer (a client signs once, not per booking).
-- answers_encrypted holds the answers as AES-GCM ciphertext (health data); only status columns are
-- plaintext so lists and badges work without decrypting. signature_name is the typed full name.
CREATE TABLE intake_form (
    id                    BIGSERIAL   PRIMARY KEY,
    business_id           BIGINT      NOT NULL REFERENCES business(id),
    square_customer_id    TEXT        NOT NULL,
    square_booking_id     TEXT        NOT NULL,
    provider_id           BIGINT      REFERENCES providers(id) ON DELETE SET NULL,
    phone_number          TEXT        NOT NULL,
    first_name            TEXT,
    token                 TEXT        NOT NULL UNIQUE,
    appointment_start_at  TIMESTAMPTZ NOT NULL,
    status                TEXT        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'SIGNED')),
    initial_sms_state     TEXT,
    reminder_sms_state    TEXT,
    sent_at               TIMESTAMPTZ,
    reminder_sent_at      TIMESTAMPTZ,
    last_manual_resend_at TIMESTAMPTZ,
    opened_at             TIMESTAMPTZ,
    signed_at             TIMESTAMPTZ,
    signed_via            TEXT        CHECK (signed_via IN ('CLIENT_FORM', 'STAFF_PAPER')),
    needs_attention       BOOLEAN     NOT NULL DEFAULT false,
    signature_name        TEXT,
    answers_encrypted     TEXT,
    square_synced_status  TEXT,
    square_sync_attempts  INT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, square_customer_id)
);

CREATE INDEX idx_intake_form_booking ON intake_form (business_id, square_booking_id);
CREATE INDEX idx_intake_form_start ON intake_form (business_id, appointment_start_at);
