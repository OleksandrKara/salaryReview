-- sms_automation predates multi-tenancy: one global row per automation_key, shared by every
-- business. The enable/disable toggle needs to become genuinely per-business — a second business
-- may want checkout_review_request off while Business A has it on, or vice versa, and future
-- businesses may need automations that don't apply to Business A at all. automation_key alone
-- can't stay the sole PK once a second business needs its own row per key, so this becomes a
-- composite PK (business_id, automation_key) — same surgery as V102 (rag_suggestion_cache).
ALTER TABLE sms_automation ADD COLUMN business_id BIGINT;
UPDATE sms_automation SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE sms_automation ALTER COLUMN business_id SET NOT NULL;

-- sms_message.automation_key and sms_reply_flow.automation_key both FK into sms_automation
-- (automation_key), which was a plain PK before this migration and remains a valid FK target
-- alone (it's still unique — no two rows share an automation_key... wait, that's no longer true
-- once a second business gets its own row for the same key). Drop those single-column FKs first —
-- Postgres won't drop sms_automation_pkey below while they still reference it. They no longer
-- describe a real constraint once (business_id, automation_key) is the real key, and
-- sms_message/sms_reply_flow already carry their own business_id (V103) to cross-check against if
-- ever needed — the FK's job (catch a typo'd automation_key) doesn't require covering business_id
-- too, but a single-column FK into a now-composite-keyed table needs automation_key to still be
-- unique alone, which it no longer is with multiple businesses. Since only Business A has rows
-- today this doesn't fire, but it's not a constraint that can be honestly kept as multi-tenant use
-- begins.
ALTER TABLE sms_message DROP CONSTRAINT IF EXISTS sms_message_automation_key_fkey;
ALTER TABLE sms_reply_flow DROP CONSTRAINT IF EXISTS sms_reply_flow_automation_key_fkey;

ALTER TABLE sms_automation DROP CONSTRAINT sms_automation_pkey;
ALTER TABLE sms_automation ADD PRIMARY KEY (business_id, automation_key);
ALTER TABLE sms_automation ADD CONSTRAINT sms_automation_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
