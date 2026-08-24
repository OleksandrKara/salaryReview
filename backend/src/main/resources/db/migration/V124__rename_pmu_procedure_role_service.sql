-- V123's table name/doc framed this as PMU-specific, but the concept — "this Square service plays
-- role X in a customer's service lifecycle" (initial visit, follow-up, periodic refresh,
-- consultation, ...) — applies to any beauty vertical (lashes, brows, facials, ...), not just
-- permanent makeup. Renamed away from the pmu_ prefix so a future business never needs its own
-- copy of this table. role was already a free VARCHAR with no CHECK constraint (see V123), so no
-- value-domain change is needed here — only the table name. The Java-side fixed enum this backed
-- is dropped in this same change (see PmuProcedureRoleService -> ServiceLifecycleRole); the rename
-- keeps the existing row (business 2's touch-up service) intact.
ALTER TABLE pmu_procedure_role_service RENAME TO service_lifecycle_role;
ALTER TABLE service_lifecycle_role RENAME CONSTRAINT pmu_procedure_role_service_business_id_role_square_variatio_key
    TO service_lifecycle_role_business_id_role_variation_key;
