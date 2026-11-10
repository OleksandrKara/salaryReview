-- Lead ownership. owner_user_id NULL means "the team" (nail leads are worked by two managers on shifts).
-- lead_owner_default: who owns leads nobody was assigned to, per scope. Today only ACADEMY (Anastasia by default).
ALTER TABLE lead_intake ADD COLUMN owner_user_id BIGINT REFERENCES app_user (id) ON DELETE SET NULL;
CREATE INDEX lead_intake_owner_idx ON lead_intake (business_id, owner_user_id) WHERE owner_user_id IS NOT NULL;

CREATE TABLE lead_owner_default (
    business_id   BIGINT      NOT NULL REFERENCES business (id),
    scope         TEXT        NOT NULL CHECK (scope IN ('ACADEMY')),
    owner_user_id BIGINT      NOT NULL REFERENCES app_user (id),
    updated_by    TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, scope)
);

INSERT INTO lead_owner_default (business_id, scope, owner_user_id, updated_by)
SELECT 2, 'ACADEMY', 42, 'migration' WHERE EXISTS (SELECT 1 FROM app_user WHERE id = 42) AND EXISTS (SELECT 1 FROM business WHERE id = 2);
