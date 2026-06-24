-- Enable pgvector. The Postgres image is now pgvector/pgvector:pg16 (official postgres:16 plus this
-- extension), so the extension is available to be created. Idempotent: safe if it already exists.
CREATE EXTENSION IF NOT EXISTS vector;
