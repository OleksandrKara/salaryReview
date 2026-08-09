-- Adds a merchant-agnostic KEYWORD rule tier: matches purely on one or more required substrings
-- (all must be present, AND semantics) in the raw description, independent of normalized_merchant.
-- Needed because some bank exports embed a per-transaction reference number directly in the
-- descriptor with no separator (e.g. Square Inc DES:SQ260701 ID:T3330NWGZD2TEDK...), which makes
-- normalized_merchant unique per transaction — the existing MERCHANT/MERCHANT_KEYWORD tiers can
-- never match twice for those descriptors since they require an exact normalized_merchant match
-- first. normalized_merchant becomes nullable to support this rule type, which has none.
ALTER TABLE merchant_rules DROP CONSTRAINT merchant_rules_rule_type_check;
ALTER TABLE merchant_rules ADD CONSTRAINT merchant_rules_rule_type_check
    CHECK (rule_type IN ('FINGERPRINT','MERCHANT','MERCHANT_KEYWORD','MERCHANT_AMOUNT_RANGE','KEYWORD'));

ALTER TABLE merchant_rules ALTER COLUMN normalized_merchant DROP NOT NULL;
