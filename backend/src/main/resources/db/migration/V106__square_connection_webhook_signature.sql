-- Phase 3.6: per-business Square webhook signature verification, replacing the single global
-- SQUARE_WEBHOOK_SIGNATURE_KEY. Nullable, no backfill here — encryption requires the app's own
-- SquareCredentialCipher + SQUARE_CREDENTIALS_MASTER_KEY, which SQL can't do (same reasoning as
-- access_token_encrypted's own migration never backfilling a real token via SQL). Business A's
-- existing production key is backfilled after deploy through the app's own
-- PUT /api/owner/settings/square endpoint, not this migration.
ALTER TABLE square_connection ADD COLUMN webhook_signature_key_encrypted TEXT;
