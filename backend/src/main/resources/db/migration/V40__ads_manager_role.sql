-- New role: ADS_MANAGER — read-only access to the marketing pages only (see Role.java). The
-- provider-link constraint already covers it correctly (role <> 'PROVIDER' requires provider_id
-- IS NULL), so only the role allow-list itself needs widening.
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check
    CHECK (role IN ('OWNER', 'MANAGER', 'PROVIDER', 'ADS_MANAGER'));
