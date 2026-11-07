-- Source chosen by hand for leads whose origin is unknown (e.g. customers staff created in Square).
ALTER TABLE lead_intake ADD COLUMN source_manual BOOLEAN NOT NULL DEFAULT FALSE;
