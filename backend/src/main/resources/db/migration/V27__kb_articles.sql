-- Knowledge Base articles: in-app, owner/manager-editable informational content (service menus,
-- booking/communication scripts, FAQ) that syncs on demand into the RAG store. Unlike SOPs there is
-- no approval workflow or version history — updated_at is the audit trail.
--
-- visible_roles (JSONB array of role names) drives per-article read access: a provider sees only
-- articles whose visible_roles contains PROVIDER; owners/managers manage all. content_hash detects
-- edits since the last sync. rag_doc_id points at this article's current rag_document; ON DELETE
-- SET NULL so an out-of-band RAG delete doesn't strand the row (KB owns the lifecycle, can re-sync).
CREATE TABLE kb_articles (
    id               BIGSERIAL    PRIMARY KEY,
    title            VARCHAR(512) NOT NULL,
    category         VARCHAR(128) NOT NULL,
    body             TEXT         NOT NULL DEFAULT '',
    visible_roles    JSONB        NOT NULL DEFAULT '["OWNER","MANAGER"]'::jsonb,
    content_hash     VARCHAR(64)  NOT NULL,
    rag_doc_id       BIGINT       REFERENCES rag_document (id) ON DELETE SET NULL,
    last_synced_at   TIMESTAMPTZ,
    last_synced_by   VARCHAR(255),
    -- NOT_SYNCED | SYNCED | CHANGED | ERROR
    sync_status      VARCHAR(32)  NOT NULL DEFAULT 'NOT_SYNCED',
    last_sync_error  TEXT,
    created_by       VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_kb_articles_category ON kb_articles (category);
CREATE INDEX idx_kb_articles_sync_status ON kb_articles (sync_status);
