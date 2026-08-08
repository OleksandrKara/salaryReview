-- MMS media attachments — one row per photo on an inbound or outbound sms_message row. Stored as
-- BYTEA directly in Postgres, same convention as staff_documents (no S3/disk storage in this app).
-- access_token is an opaque public identifier (mirrors sms_message.click_token's ClickTokens
-- convention) so both the dashboard's <img> tags and Twilio's own outbound-media-fetch requests can
-- retrieve a file via /api/public/sms-media/{token} with no session/auth header.
CREATE TABLE sms_message_media (
    id             BIGSERIAL   PRIMARY KEY,
    sms_message_id BIGINT      NOT NULL REFERENCES sms_message (id) ON DELETE CASCADE,
    content_type   TEXT        NOT NULL,
    file_data      BYTEA       NOT NULL,
    access_token   TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX sms_message_media_sms_message_id_idx ON sms_message_media (sms_message_id);
CREATE UNIQUE INDEX sms_message_media_access_token_idx ON sms_message_media (access_token);
