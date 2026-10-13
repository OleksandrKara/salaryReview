-- ai-visibility: runs survive a backend restart (2026-10-13: an owner-started multi-question
-- re-check was cut off by a blue/green deploy). query_ids = the questions a manual run covers
-- (null for the weekly run = every ACTIVE question); claimed_at = heartbeat of the replica working
-- on it, so exactly one replica resumes a run whose heartbeat went stale.
ALTER TABLE ai_visibility_run ADD COLUMN query_ids JSONB;
ALTER TABLE ai_visibility_run ADD COLUMN claimed_at TIMESTAMPTZ;
