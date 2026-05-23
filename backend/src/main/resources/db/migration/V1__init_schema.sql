CREATE TABLE providers (
    id                  BIGSERIAL PRIMARY KEY,
    name                TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    commission_rate     NUMERIC(5,4) NOT NULL DEFAULT 0.4500,
    card_tip_fee_rate   NUMERIC(5,4) NOT NULL DEFAULT 0.0350,
    active              BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE pay_periods (
    id      BIGSERIAL PRIMARY KEY,
    year    INT NOT NULL,
    month   INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    half    VARCHAR(10) NOT NULL CHECK (half IN ('FIRST', 'SECOND')),
    label   TEXT NOT NULL,
    UNIQUE (year, month, half)
);

CREATE TABLE period_entries (
    id                  BIGSERIAL PRIMARY KEY,
    provider_id         BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    pay_period_id       BIGINT NOT NULL REFERENCES pay_periods(id) ON DELETE CASCADE,
    procedures          INT NOT NULL DEFAULT 0,
    card_total          NUMERIC(10,2) NOT NULL DEFAULT 0,
    cash_total          NUMERIC(10,2) NOT NULL DEFAULT 0,
    card_tips           NUMERIC(10,2) NOT NULL DEFAULT 0,
    adjustments_amount  NUMERIC(10,2) NOT NULL DEFAULT 0,
    adjustments_note    TEXT,
    UNIQUE (provider_id, pay_period_id)
);

CREATE TABLE salon_config (
    id                  INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    owner_short_name    TEXT NOT NULL DEFAULT 'AK'
);

INSERT INTO salon_config (id, owner_short_name) VALUES (1, 'AK');
