-- What changed vs. the previous version, in the author's own words (optionally AI-drafted/translated).
-- Shown to staff reviewing v2+ as a short "what's new" notice; null/blank shows nothing.
ALTER TABLE sop_versions ADD COLUMN change_note TEXT;
ALTER TABLE sop_versions ADD COLUMN change_note_ru TEXT;
