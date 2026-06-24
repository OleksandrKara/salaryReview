-- One row per uploaded source document for the RAG knowledge assistant. A document lands PENDING
-- and is NOT chunked/classified/embedded until an OWNER explicitly approves it (the human pre-upload
-- gate). Ingestion moves it PENDING -> INDEXED (or FAILED); deletion is handled by cascading to
-- rag_chunk while a redaction audit row (V26) survives.
CREATE TABLE rag_document (
    id              BIGSERIAL    PRIMARY KEY,
    filename        VARCHAR(512) NOT NULL,
    -- PDF | MARKDOWN | TEXT — how the bytes were parsed.
    source_type     VARCHAR(32)  NOT NULL,
    -- Plain text extracted at upload time, so the admin can preview it (and approval can chunk from
    -- it) without re-uploading the original bytes. The original file bytes are not retained.
    extracted_text  TEXT         NOT NULL,
    -- PENDING | INDEXING | INDEXED | QUARANTINED | FAILED
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- Free-text reason when status is FAILED (parse/ingest error). Null otherwise.
    status_detail   TEXT,
    uploaded_by     VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    indexed_at      TIMESTAMPTZ
);

CREATE INDEX idx_rag_document_status ON rag_document (status);
