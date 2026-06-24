-- Versioned configuration for the answering agent. Unlike triage (prompt version is a Java string
-- constant, deploy-to-change), the RAG agent is owner-tunable at runtime, so config lives in the DB
-- and every update inserts a new version rather than mutating the active one. Each answer records
-- which version produced it, enabling LangSmith A/B exactly like triage prompt versions.
CREATE TABLE rag_agent_config (
    version            INT          PRIMARY KEY,
    system_prompt      TEXT         NOT NULL,
    model              VARCHAR(64)  NOT NULL,
    -- Valid on Haiku 4.5 / Sonnet 4.6. NOTE: removed (400) on Opus 4.7+/Fable — escalating the
    -- model means dropping this knob and steering via the effort parameter instead.
    temperature        NUMERIC(3,2) NOT NULL,
    -- Top-k chunks to retrieve, and the max cosine distance beyond which a chunk is treated as a
    -- non-match (so an out-of-corpus question yields empty context -> a "don't know" answer).
    k                  INT          NOT NULL,
    distance_threshold NUMERIC(4,3) NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Only one active version at a time.
CREATE UNIQUE INDEX uq_rag_agent_config_active ON rag_agent_config (active) WHERE active;

-- Seed version 1 (active). The prompt enforces grounded, cited answers and the "say you don't know"
-- contract; tune via new versions, never by editing this row.
INSERT INTO rag_agent_config (version, system_prompt, model, temperature, k, distance_threshold, active)
VALUES (
    1,
    'You are the salon''s knowledge assistant for managers and owners. Answer ONLY using the provided document context. Cite the source document for every claim. If the context does not contain the answer, say you do not know and do not guess.',
    'claude-haiku-4-5',
    0.00,
    6,
    0.600,
    true
);
