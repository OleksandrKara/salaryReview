-- Square Application ID (sq0idp-...) — not consumed anywhere in this app's current personal-
-- access-token model (it's an OAuth client id, relevant only to the not-yet-built Phase 2 OAuth
-- flow per SquareProperties' own doc comment), but the owner wants it recorded alongside the
-- other Square-connection fields in the admin UI for reference. Nullable, purely informational.
ALTER TABLE square_connection ADD COLUMN application_id VARCHAR(64);
