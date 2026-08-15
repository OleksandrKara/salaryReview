-- Phase 2.5 follow-up: the cross-tenant isolation test suite proved these two old global unique
-- constraints (deliberately kept by V87 "until the PR that actually onboards a second business",
-- see V87's comment) are still live and would reject a second business's pay period or revenue
-- snapshot the instant it shared a (year, month, half) or calendar date with business A's — which
-- for revenue_snapshot is guaranteed on day one, since every business snapshots daily.
--
-- Unlike app_user_username_key (kept — see AppUserRepository.findByUsername's Javadoc: login has no
-- business-picker yet, so username must stay globally unique until one exists), nothing in the
-- codebase still queries pay_periods or revenue_snapshot without a business_id predicate — grepped
-- for any lingering unscoped lookup and found none. The new composite uniques
-- (pay_periods_business_year_month_half_uq, revenue_snapshot_business_snapshot_date_uq) added by V87
-- are sufficient on their own.

ALTER TABLE pay_periods DROP CONSTRAINT pay_periods_year_month_half_key;
ALTER TABLE revenue_snapshot DROP CONSTRAINT revenue_snapshot_snapshot_date_key;
