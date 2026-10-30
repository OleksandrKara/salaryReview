-- Optional per-user, per-business permissions on top of the role (first one: SMS_INBOX, so a
-- provider can read and reply to customer texts). A missing row means "not granted".
CREATE TABLE user_permission (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES business(id),
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    permission  VARCHAR(40) NOT NULL,
    granted_by  BIGINT REFERENCES app_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, user_id, permission)
);
