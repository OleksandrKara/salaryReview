CREATE TABLE business (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    short_code  TEXT NOT NULL UNIQUE,
    timezone    TEXT NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO business (name, short_code, timezone)
VALUES ('AK.LUX.NAILS', 'akluxnails', 'America/Los_Angeles');
