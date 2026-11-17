-- Owner-recorded meal waivers (signed outside the app, e.g. SignNow) with explicit effective dates,
-- a link to the stored document, and a revocation effective date.
ALTER TABLE meal_waiver_agreement
    ADD COLUMN effective_from     DATE,
    ADD COLUMN revoked_effective  DATE,
    ADD COLUMN document_id        BIGINT REFERENCES staff_documents(id) ON DELETE SET NULL,
    ADD COLUMN recorded_by        TEXT,
    ADD COLUMN revoked_by         TEXT;
