-- Durable, app-wide cache for the chat's grounded starter prompts. Previously in-memory, so it was
-- regenerated (an LLM call) on every backend restart/redeploy and per process. One row per language;
-- signature is the corpus fingerprint (so new/removed docs invalidate it) and generated_at drives the
-- 24h TTL. Shared by all users, survives restarts → at most one generation per language per day.
CREATE TABLE rag_suggestion_cache (
    language     VARCHAR(8)  PRIMARY KEY,   -- EN | RU
    signature    TEXT        NOT NULL,
    payload      JSONB       NOT NULL,      -- serialized StarterSuggestions
    generated_at TIMESTAMPTZ NOT NULL
);
