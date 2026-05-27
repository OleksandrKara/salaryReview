-- Per-salon commission settings, replacing the values that were previously hardcoded.
-- Defaults match AK.LUX.NAILS: 60-service month tier, 45/55 base, 50/50 tier, 3.5% card tip fee,
-- and a $60 cutoff below which a service (e.g. a design) doesn't count toward the tier.
ALTER TABLE salon_config
    ADD COLUMN tier_service_threshold INT           NOT NULL DEFAULT 60,
    ADD COLUMN service_price_cutoff   NUMERIC(10,2)  NOT NULL DEFAULT 60.00,
    ADD COLUMN base_commission_rate   NUMERIC(5,4)   NOT NULL DEFAULT 0.4500,
    ADD COLUMN tier_commission_rate   NUMERIC(5,4)   NOT NULL DEFAULT 0.5000,
    ADD COLUMN card_tip_fee_rate      NUMERIC(5,4)   NOT NULL DEFAULT 0.0350;
