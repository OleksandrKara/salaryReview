-- Optional per-business mapping from a Square catalog variation_id to what role it plays in a
-- customer's PMU procedure lifecycle (touch-up, color booster, initial procedure, consultation) —
-- see openspec proposal for Business 2 automations #3/#4 (touch-up reminder, annual color-booster
-- reminder). Deliberately data, not code: business 2's own Square catalog has 9 real touch-up
-- variations (per provider x per time window) and 8 color-booster variations, discovered live via
-- a Square catalog listing on 2026-08-23 — hardcoding any of them into Java would mean a deploy for
-- every future addition/correction. No row for a (business, role) pair means that role has nothing
-- configured yet, and any automation gated on it stays inert — same "absent = off" convention as
-- business.google_review_url/feedback_form_url.
CREATE TABLE pmu_procedure_role_service (
    id                  BIGSERIAL    PRIMARY KEY,
    business_id         BIGINT       NOT NULL,
    role                VARCHAR(32)  NOT NULL,
    square_variation_id VARCHAR(64)  NOT NULL,
    created_by          VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (business_id, role, square_variation_id)
);

-- 2026-08-24: business 2 (AK PMU) — Anastasiia's 1-4 month touch-up variation, confirmed with the
-- owner as the first (of eventually up to 9) qualifying touch-up service for the "initial
-- procedure -> ~4 week touch-up reminder" automation. Not yet consumed by any automation.
INSERT INTO pmu_procedure_role_service (business_id, role, square_variation_id, created_by)
VALUES (2, 'TOUCH_UP', 'P5CCSK4COM4QJH53KDSK4R7U', 'owner');
