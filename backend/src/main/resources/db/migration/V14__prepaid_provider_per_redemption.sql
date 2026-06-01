-- A prepaid customer can spend their package across several providers, so a package is no longer tied
-- to one provider. Instead each confirmed draw-down records the provider who actually performed that
-- service, and the settlement credits them. Backfill existing redemptions from their package's (old)
-- provider so historical payouts don't change, then drop the provider from the package.
ALTER TABLE prepaid_redemption ADD COLUMN provider_id BIGINT REFERENCES providers(id);

UPDATE prepaid_redemption r
   SET provider_id = p.provider_id
  FROM prepaid_package p
 WHERE p.id = r.package_id;

ALTER TABLE prepaid_redemption ALTER COLUMN provider_id SET NOT NULL;

ALTER TABLE prepaid_package DROP COLUMN provider_id;
