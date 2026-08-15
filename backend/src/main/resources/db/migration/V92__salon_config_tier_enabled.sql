-- Lets a business opt out of the tier-bonus mechanic entirely (flat commission rate, no
-- exceptions) rather than just setting an unreachably-high threshold — a manual TierGrant
-- override would still force qualification through a threshold-based "disable", which isn't a
-- real "no exceptions" guarantee. DEFAULT TRUE preserves every existing business's behavior
-- (including business A's real 50/50 tier) with no explicit backfill needed.
ALTER TABLE salon_config ADD COLUMN tier_enabled BOOLEAN NOT NULL DEFAULT TRUE;
