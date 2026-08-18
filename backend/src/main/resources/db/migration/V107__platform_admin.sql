-- Phase 5.2 (design.md D4): a narrow, additive "you, managing every business's onboarding" flag —
-- not a Role enum value, not scoped to a business_membership row. Checked only by the handful of
-- /api/platform/** endpoints (PlatformBusinessController); every other authorization matcher in
-- SecurityConfig is untouched. Before this, /api/platform/** was gated on "any authenticated
-- OWNER" — any business's owner could list every business on the platform and create new ones
-- with arbitrary owner credentials, not just the platform's real operator.
--
-- Deliberately no data seeded here: Flyway migrations run at app startup BEFORE OwnerBootstrap's
-- ApplicationRunner creates the very first app_user row, so a fresh environment's app_user table
-- is still empty at this exact moment — a SELECT-based INSERT here would silently seed zero rows.
-- Seeding is application code instead (OwnerBootstrap, both the fresh-bootstrap path and a
-- one-time backfill for an already-bootstrapped instance like production), where the owner's real
-- id is actually known.
CREATE TABLE platform_admin (
    user_id BIGINT PRIMARY KEY REFERENCES app_user(id)
);
