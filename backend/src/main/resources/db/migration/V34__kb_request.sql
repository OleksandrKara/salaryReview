-- Knowledge-gap requests: when the assistant can't answer a question, the asker (owner/manager) can
-- file a request to have it added to the knowledge base. The owner reviews the list on /rag/admin and
-- resolves each by extending a KB article or SOP (then re-syncing). target is the asker's hint at what
-- should be extended (KB / SOP / unsure); status tracks the owner's triage.
CREATE TABLE kb_request (
    id            BIGSERIAL    PRIMARY KEY,
    question      TEXT         NOT NULL,
    note          TEXT,
    target        VARCHAR(16)  NOT NULL DEFAULT 'UNSURE',  -- KB | SOP | UNSURE
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',    -- OPEN | RESOLVED | DISMISSED
    requested_by  VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    resolved_by   VARCHAR(255)
);

CREATE INDEX idx_kb_request_status ON kb_request (status);
