CREATE TABLE lead_reminders (
  id          BIGSERIAL PRIMARY KEY,
  business_id BIGINT      NOT NULL,
  lead_id     BIGINT      NOT NULL REFERENCES lead_intake (id) ON DELETE CASCADE,
  due_at      TIMESTAMPTZ NOT NULL,
  note        TEXT,
  origin      TEXT        NOT NULL DEFAULT 'MANUAL',
  fired_at    TIMESTAMPTZ,
  done_at     TIMESTAMPTZ,
  created_by  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lead_reminders_open_idx ON lead_reminders (lead_id) WHERE done_at IS NULL;
CREATE INDEX lead_reminders_due_idx ON lead_reminders (due_at) WHERE done_at IS NULL AND fired_at IS NULL;

INSERT INTO lead_reminders (business_id, lead_id, due_at, origin, fired_at, created_by)
SELECT business_id, id, callback_at, 'LEGACY', CASE WHEN callback_reminded THEN callback_at END, updated_by
FROM lead_intake
WHERE callback_at IS NOT NULL AND status IN ('NEW', 'CONTACTED');
