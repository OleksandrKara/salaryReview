-- Phase 2.1: direct business_id column on the core payroll/commission tables (design.md D8 —
-- explicit business_id predicates are the primary tenant boundary; Hibernate @Filter is deferred to
-- when it has real cross-tenant tables to guard, see tasks.md 1.8).
--
-- Additive only, per tasks.md's rollback strategy: the OLD global unique constraints
-- (app_user.username, pay_periods(year,month,half), revenue_snapshot.snapshot_date) are left in
-- place. They stay strictly stronger than the new composite ones below until the PR that actually
-- onboards a second business drops them alongside the code that stops relying on global uniqueness —
-- so this migration alone is fully revertible without leaving the schema mid-way.

ALTER TABLE providers ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE app_user ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE pay_periods ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE revenue_snapshot ADD COLUMN business_id BIGINT REFERENCES business(id);

UPDATE providers SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE app_user SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE pay_periods SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE revenue_snapshot SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');

ALTER TABLE providers ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE app_user ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE pay_periods ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE revenue_snapshot ALTER COLUMN business_id SET NOT NULL;

ALTER TABLE app_user ADD CONSTRAINT app_user_business_username_uq UNIQUE (business_id, username);
ALTER TABLE pay_periods ADD CONSTRAINT pay_periods_business_year_month_half_uq
    UNIQUE (business_id, year, month, half);
ALTER TABLE revenue_snapshot ADD CONSTRAINT revenue_snapshot_business_snapshot_date_uq
    UNIQUE (business_id, snapshot_date);
