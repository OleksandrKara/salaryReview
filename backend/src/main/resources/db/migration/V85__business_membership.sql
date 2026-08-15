CREATE TABLE business_membership (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT      NOT NULL REFERENCES business(id),
    user_id     BIGINT      NOT NULL REFERENCES app_user(id),
    role        VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, user_id)
);

INSERT INTO business_membership (business_id, user_id, role)
SELECT (SELECT id FROM business WHERE short_code = 'akluxnails'), id, role
FROM app_user;
