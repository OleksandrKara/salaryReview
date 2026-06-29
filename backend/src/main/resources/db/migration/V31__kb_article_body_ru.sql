-- Russian translation of a KB article body (nullable). English (body) stays the default and the
-- fallback: when body_ru is null, the reader and the assistant use English. It's authored once via an
-- "AI translate" action, then editable. Sync indexes both languages so the assistant can retrieve in
-- either; content_hash now covers both bodies so a Russian edit also marks the article for re-sync.
ALTER TABLE kb_articles ADD COLUMN body_ru TEXT;
