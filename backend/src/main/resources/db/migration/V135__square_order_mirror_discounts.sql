-- Extends square_order (Phase 1, V134) with the fields SquareMonthAggregator's discount-coverage
-- policy needs, which weren't captured when this mirror was built purely for marketing reads (see
-- SquareOrderMirror's own doc, updated alongside this migration) — order-level discounts and, per
-- line item, its name and applied discounts (added to the existing line_items_json shape, no new
-- column needed there). Additive only: existing rows keep their old line_items_json shape until
-- the next upsert (webhook or reconciliation) re-populates it with the richer one.
ALTER TABLE square_order ADD COLUMN discounts_json JSONB;
