-- rag_document predates multi-tenancy and had zero tenant boundary (tasks.md 2.6). Root table, no
-- existing FK into an already business-scoped table, so it needs a real business_id column, same
-- treatment as sops (V97) / kb_articles (V98).
ALTER TABLE rag_document ADD COLUMN business_id BIGINT;
UPDATE rag_document SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE rag_document ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE rag_document ADD CONSTRAINT rag_document_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_rag_document_business_id ON rag_document (business_id);

-- rag_chunk gets no business_id column of its own — every access joins through
-- rag_document.business_id via its document_id FK, same idiom as staff_documents/sop_versions.
