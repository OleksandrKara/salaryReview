-- Data-correctness fix, found while building the owner-facing picker UI for this table: the row
-- seeded in V123 for business 2's touch-up service stored P5CCSK4COM4QJH53KDSK4R7U, which is the
-- Square catalog ITEM id ("Touch-Up by Anastasiia (1-4 month)") — not its variation id. An order
-- line/AttributedService always carries the variation id, never the parent item id, so the stored
-- value would never have matched anything a real eligibility query compared it against. The
-- correct variation id (its "Regular" variation) is E5BZJGW3T2DV7LKXT5KXITRT, confirmed against
-- the same live Square catalog listing used to find the original candidates.
UPDATE service_lifecycle_role
SET square_variation_id = 'E5BZJGW3T2DV7LKXT5KXITRT'
WHERE business_id = 2 AND role = 'TOUCH_UP' AND square_variation_id = 'P5CCSK4COM4QJH53KDSK4R7U';
