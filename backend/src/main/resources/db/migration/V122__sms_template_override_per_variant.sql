-- Owner-editable SMS wording moves from "one override replaces every rotating variant" to
-- per-variant overrides — see SmsMessageTemplateCatalog's own doc on why a template can now have
-- several default bodies. An owner can now tweak just variant 3 of 5 and leave the rest rotating
-- on their in-code defaults, or fully customize all 5, instead of a single edit collapsing the
-- whole rotation down to one line. variant_index 0 for a single-variant template key behaves
-- exactly like the old one-row-per-key override always did.
--
-- No existing data to migrate — sms_template_override is empty in production as of this
-- migration (no business has customized a template yet).
ALTER TABLE sms_template_override DROP CONSTRAINT sms_template_override_business_id_template_key_key;
ALTER TABLE sms_template_override ADD COLUMN variant_index INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sms_template_override ADD CONSTRAINT sms_template_override_business_key_variant_key
    UNIQUE (business_id, template_key, variant_index);
