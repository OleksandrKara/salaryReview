-- rag_redaction_audit predates multi-tenancy. Standalone (no FK to rag_document by design — it must
-- survive the deleted document row), so it needs its own business_id column rather than a join.
ALTER TABLE rag_redaction_audit ADD COLUMN business_id BIGINT;
UPDATE rag_redaction_audit SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE rag_redaction_audit ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE rag_redaction_audit ADD CONSTRAINT rag_redaction_audit_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_rag_redaction_audit_business_id ON rag_redaction_audit (business_id);
