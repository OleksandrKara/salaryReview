-- Russian translation of a SOP version's body (nullable), mirroring KB articles (V31). English (body)
-- stays the default and the fallback: when body_ru is null, the reader and the assistant use English.
-- Sync indexes both languages so the assistant can retrieve in either. Versions are immutable, so a
-- translation is part of the version it's created with (a new translation = a new version).
ALTER TABLE sop_versions ADD COLUMN body_ru TEXT;
