-- Found while migrating NoShowFeeService onto SquareClientProvider (Phase 3.5): no_show_fee_override
-- was missed by Phase 2.3/2.4's business-scoping pass. It can't take Phase 2.3's usual
-- join-through-provider_id treatment (TierGrant/Redo/etc.) because provider_id is nullable here — a
-- SUPPRESS-kind override has no provider at all (see NoShowFeeOverride's own doc comment), so an
-- inner join would silently drop exactly the rows the suppress feature depends on. Same direct-column
-- treatment as V88's no-FK-path tables instead.

ALTER TABLE no_show_fee_override ADD COLUMN business_id BIGINT REFERENCES business(id);
UPDATE no_show_fee_override SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE no_show_fee_override ALTER COLUMN business_id SET NOT NULL;
