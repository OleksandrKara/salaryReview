-- rag_suggestion_cache predates multi-tenancy: one global row per language (EN/RU), shared by every
-- business. Its PK must become composite (business_id, language) so each business gets its own
-- cached starter prompts — same boolean-singleton-to-real-key surgery as V95/V96, composite instead
-- of surrogate since (business_id, language) is itself a natural key here.
ALTER TABLE rag_suggestion_cache ADD COLUMN business_id BIGINT;
UPDATE rag_suggestion_cache SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE rag_suggestion_cache ALTER COLUMN business_id SET NOT NULL;

ALTER TABLE rag_suggestion_cache DROP CONSTRAINT rag_suggestion_cache_pkey;
ALTER TABLE rag_suggestion_cache ADD PRIMARY KEY (business_id, language);
ALTER TABLE rag_suggestion_cache ADD CONSTRAINT rag_suggestion_cache_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
