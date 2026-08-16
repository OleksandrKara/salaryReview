-- rag_agent_config predates multi-tenancy. Add a real business_id column and re-scope the "only one
-- active config" constraint to be per-business instead of global. The `version` PK and its numbering
-- (RagConfigService: MAX(version)+1, a global monotonic counter) are left unchanged — scoping version
-- numbering per business would require a composite PK for no real benefit while there's only one
-- business actually using RAG.
ALTER TABLE rag_agent_config ADD COLUMN business_id BIGINT;
UPDATE rag_agent_config SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE rag_agent_config ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE rag_agent_config ADD CONSTRAINT rag_agent_config_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);

DROP INDEX uq_rag_agent_config_active;
CREATE UNIQUE INDEX uq_rag_agent_config_active ON rag_agent_config (business_id, active) WHERE active;
