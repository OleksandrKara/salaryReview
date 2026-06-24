-- One row per chunk of an approved document. The PII/relevance gate runs BEFORE embedding, so a
-- chunk flagged as PII or irrelevant is stored QUARANTINED with a null embedding and is never sent
-- to Voyage and never retrievable. Only INDEXED chunks carry a 1024-dim embedding (voyage-3.5) and
-- participate in nearest-neighbour search. char_start/end + content_sha256 give post-hoc
-- traceability; the FK cascade removes vectors when a document is deleted.
CREATE TABLE rag_chunk (
    id                BIGSERIAL    PRIMARY KEY,
    document_id       BIGINT       NOT NULL REFERENCES rag_document (id) ON DELETE CASCADE,
    ordinal           INT          NOT NULL,
    chunk_text        TEXT         NOT NULL,
    char_start        INT          NOT NULL,
    char_end          INT          NOT NULL,
    content_sha256    VARCHAR(64)  NOT NULL,
    -- INDEXED | QUARANTINED
    status            VARCHAR(32)  NOT NULL,
    -- Why a chunk was quarantined (pii:<types> | irrelevant). Null for INDEXED chunks.
    quarantine_reason TEXT,
    -- 1024 dims = voyage-3.5 default. Null for QUARANTINED chunks (never embedded).
    embedding         vector(1024),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_rag_chunk_doc_ordinal UNIQUE (document_id, ordinal)
);

-- HNSW with cosine distance: better recall/latency than IVFFlat and no training step. Only INDEXED
-- chunks have a non-null embedding, so the index naturally covers just the retrievable set.
CREATE INDEX idx_rag_chunk_embedding
    ON rag_chunk USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_rag_chunk_document ON rag_chunk (document_id);
