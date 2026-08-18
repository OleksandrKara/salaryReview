-- Phase 4.3 (multi-tenant-salon-platform): per-business gate for the optional AI/RAG feature
-- set — today these 5 keys are all globally on (env vars: RAG_ENABLED, AI_TRIAGE_ENABLED,
-- AI_FUNNEL_ANALYSIS_ENABLED, AI_SMS_DRAFT_ENABLED, RAG_SUGGESTIONS_ENABLED), which meant AK PMU
-- (business_id=2) silently got every AI feature the moment it was onboarded, despite never having
-- asked for or been sold any of them. Commission/Square/settlements stay core and unconditional —
-- not gated here.
--
-- Business A (id=1) is seeded enabled=true for all 5 keys, matching today's actual global config
-- exactly (verified against the live container's env before writing this migration) — this
-- migration changes nothing observable for Business A. Business B gets no rows at all (missing
-- row = disabled, same effective value as an explicit enabled=false row) — this is the real
-- behavior change: AK PMU loses the RAG assistant widget, AI triage Explain button, funnel
-- analysis, and SMS draft suggestions until explicitly turned on for it.
CREATE TABLE business_feature (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES business(id),
    feature_key TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (business_id, feature_key)
);

INSERT INTO business_feature (business_id, feature_key, enabled)
SELECT 1, key, true
FROM (VALUES ('rag.enabled'), ('ai.triage.enabled'), ('ai.funnel-analysis.enabled'),
             ('ai.sms-draft.enabled'), ('rag.suggestions.enabled')) AS keys(key);
