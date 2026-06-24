-- Audit trail that outlives a deleted document. When an OWNER deletes a rag_document, its chunks and
-- their vectors are cascade-removed (no longer retrievable), but this row remains so there is a
-- record of what was purged, by whom, and when — the accountability half of the safety story, and a
-- foundation for the future GDPR/SaaS deletion requirements. Intentionally NOT a foreign key: it
-- must survive the document row it refers to.
CREATE TABLE rag_redaction_audit (
    id            BIGSERIAL    PRIMARY KEY,
    document_id   BIGINT       NOT NULL,
    filename      VARCHAR(512) NOT NULL,
    chunk_count   INT          NOT NULL,
    deleted_by    VARCHAR(255) NOT NULL,
    deleted_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
