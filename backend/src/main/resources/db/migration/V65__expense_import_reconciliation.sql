CREATE TABLE bank_statement_imports (
    id                      BIGSERIAL PRIMARY KEY,
    original_filename       TEXT NOT NULL,
    raw_file                BYTEA NOT NULL,
    row_count               INT NOT NULL,
    statement_period_start  DATE,
    statement_period_end    DATE,
    status                  TEXT NOT NULL DEFAULT 'AWAITING_REVIEW'
                             CHECK (status IN ('AWAITING_REVIEW','COMPLETED','REVERTED')),
    uploaded_by             VARCHAR(100),
    uploaded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    reverted_at             TIMESTAMPTZ
);

CREATE TABLE merchant_aliases (
    id                  BIGSERIAL PRIMARY KEY,
    raw_pattern         TEXT NOT NULL UNIQUE,
    canonical_merchant  TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE merchant_rules (
    id                      BIGSERIAL PRIMARY KEY,
    rule_type               TEXT NOT NULL CHECK (rule_type IN
                             ('FINGERPRINT','MERCHANT','MERCHANT_KEYWORD','MERCHANT_AMOUNT_RANGE')),
    normalized_merchant      TEXT NOT NULL,
    keyword                  TEXT,
    amount_min               NUMERIC(10,2),
    amount_max               NUMERIC(10,2),
    fingerprint              TEXT,
    category                 TEXT NOT NULL,
    active                   BOOLEAN NOT NULL DEFAULT true,
    created_by               VARCHAR(100),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    times_applied            INT NOT NULL DEFAULT 0,
    last_applied_at          TIMESTAMPTZ,
    source_transaction_id    BIGINT
);
-- At most one active plain merchant-level rule per merchant — forces a conflict prompt instead of
-- silently overwriting a previously-learned category for the same merchant (design.md D9).
CREATE UNIQUE INDEX idx_merchant_rules_one_default_per_merchant
    ON merchant_rules (normalized_merchant)
    WHERE rule_type = 'MERCHANT' AND active;
CREATE INDEX idx_merchant_rules_lookup ON merchant_rules (normalized_merchant, rule_type, active);

CREATE TABLE bank_transactions (
    id                          BIGSERIAL PRIMARY KEY,
    import_id                   BIGINT NOT NULL REFERENCES bank_statement_imports(id),
    transaction_date             DATE NOT NULL,
    raw_description               TEXT NOT NULL,
    normalized_merchant           TEXT NOT NULL,
    merchant_key                  TEXT NOT NULL,
    amount                        NUMERIC(10,2) NOT NULL,
    fingerprint                   TEXT NOT NULL,
    occurrence_index               INT NOT NULL DEFAULT 0,
    status                        TEXT NOT NULL DEFAULT 'UNMATCHED' CHECK (status IN
                                  ('UNMATCHED','AUTO_MATCHED','NEEDS_REVIEW','REVIEWED','EXCLUDED','DUPLICATE')),
    matched_rule_id                BIGINT REFERENCES merchant_rules(id),
    match_reason                   TEXT,
    confidence                     NUMERIC(3,2),
    category                       TEXT,
    excluded_reason                TEXT CHECK (excluded_reason IN
                                    ('TRANSFER','CREDIT_CARD_PAYMENT','PAYROLL','TAX',
                                     'OWNER_CONTRIBUTION','CASH_WITHDRAWAL','REFUND','OTHER')),
    linked_expense_entry_id        BIGINT REFERENCES expense_entries(id),
    duplicate_of_transaction_id    BIGINT REFERENCES bank_transactions(id),
    reviewed_by                    VARCHAR(100),
    reviewed_at                    TIMESTAMPTZ
);
CREATE INDEX idx_bank_transactions_import ON bank_transactions (import_id);
CREATE INDEX idx_bank_transactions_status ON bank_transactions (import_id, status);
CREATE INDEX idx_bank_transactions_fingerprint ON bank_transactions (fingerprint);
CREATE INDEX idx_bank_transactions_merchant ON bank_transactions (normalized_merchant);

-- Trigram similarity for the fuzzy-match fallback tier (design.md D2) — deterministic and
-- explainable, not a black-box embedding.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_bank_transactions_merchant_trgm
    ON bank_transactions USING gin (merchant_key gin_trgm_ops);

ALTER TABLE merchant_rules
    ADD CONSTRAINT fk_merchant_rules_source_transaction
    FOREIGN KEY (source_transaction_id) REFERENCES bank_transactions(id);
