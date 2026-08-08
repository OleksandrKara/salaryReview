-- One emoji reaction per sms_message row — Apple's tapback-over-SMS fallback text (e.g.
-- `Loved "message"`, sent as a literal SMS when the reacting party isn't on iMessage), detected
-- and matched back to the salon's own recent outbound message — see SmsReactionService. A re-tap
-- with a different reaction updates this same row rather than accumulating duplicates.
CREATE TABLE sms_message_reaction (
    id             BIGSERIAL   PRIMARY KEY,
    sms_message_id BIGINT      NOT NULL REFERENCES sms_message (id) ON DELETE CASCADE,
    emoji          TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX sms_message_reaction_sms_message_id_idx ON sms_message_reaction (sms_message_id);
