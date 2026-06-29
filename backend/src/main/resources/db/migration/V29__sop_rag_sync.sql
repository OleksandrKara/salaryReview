-- Sync a SOP's current published version into the RAG store so the assistant can answer from SOPs,
-- mirroring how KB articles sync (see V27). rag_doc_id points at this SOP's current rag_document,
-- ON DELETE SET NULL so an out-of-band RAG delete doesn't strand the row (the SOP owns the lifecycle
-- and can re-sync). synced_version_id records WHICH version is in the store, so publishing a new
-- version surfaces as CHANGED. Only ACTIVE SOPs with a published version are syncable; archiving a
-- synced SOP retires its rag_document.
ALTER TABLE sops
    ADD COLUMN rag_doc_id        BIGINT      REFERENCES rag_document (id) ON DELETE SET NULL,
    ADD COLUMN synced_version_id BIGINT,
    ADD COLUMN last_synced_at    TIMESTAMPTZ,
    ADD COLUMN last_synced_by    VARCHAR(255),
    -- NOT_SYNCED | SYNCED | CHANGED | ERROR
    ADD COLUMN sync_status       VARCHAR(32) NOT NULL DEFAULT 'NOT_SYNCED',
    ADD COLUMN last_sync_error   TEXT;

CREATE INDEX idx_sops_sync_status ON sops (sync_status);
