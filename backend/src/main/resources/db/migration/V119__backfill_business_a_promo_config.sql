-- Business A's REBOOK10/WINBACK5 Square Catalog/CustomerGroup objects already existed (created
-- manually before business_promo_config existed) but were never captured anywhere in this app —
-- only their group ids lived in env vars (RebookingProperties). PromoConfigService's legacy
-- fallback filled the gap with amount/min-spend guesses, but refused every save() for Business A
-- outright to avoid ever creating a second, conflicting set of Square objects for an account
-- whose discount already worked.
--
-- Looked up live against the real Square account (2026-08-20): both pricing rules' real
-- minimum_order_subtotal_money is $99, not the "no minimum" the REBOOK10 fallback assumed —
-- that guess was wrong (the discount itself was still applying correctly on Square's side
-- regardless; only this app's own displayed/assumed terms were incomplete). Backfilling the real
-- ids here — see PromoConfigService's own updated doc — unblocks the owner editing Business A's
-- terms like any other business, since save() now updates real, known objects instead of
-- guessing at ones that were never recorded.
INSERT INTO business_promo_config (business_id, promo_code, discount_cents, min_spend_cents,
    square_customer_group_id, square_discount_catalog_id, square_pricing_rule_catalog_id,
    square_product_set_catalog_id, updated_at, updated_by)
SELECT id, 'REBOOK10', 1000, 9900, '56EKNTWTEQKEC850T9AEWGX20R', '4I4YG3FSTTMWIIS5SDIHCQBV',
    'IX7IUCLGNZBHOWLKA6TNS3WI', 'KDA5QP6OW2MSGISKT3QQCOEI', now(), 'migration_v119'
FROM business WHERE short_code = 'akluxnails';

INSERT INTO business_promo_config (business_id, promo_code, discount_cents, min_spend_cents,
    square_customer_group_id, square_discount_catalog_id, square_pricing_rule_catalog_id,
    square_product_set_catalog_id, updated_at, updated_by)
SELECT id, 'WINBACK5', 500, 9900, '62D66B1K8050MP6312141Q3W2C', 'BGSC6SD2C2FOBDP4V5XS435L',
    'L6XD7N2UJZSO4KB3PJHUPD7Y', 'KDA5QP6OW2MSGISKT3QQCOEI', now(), 'migration_v119'
FROM business WHERE short_code = 'akluxnails';
