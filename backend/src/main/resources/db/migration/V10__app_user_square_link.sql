-- Phase 2 (Square assist): optionally link an account back to its Square team member, and keep the
-- email. Lets the add-user UI pre-fill from Square (role suggested from job title / is_owner) and
-- detect who already has an account. Nullable — manual accounts need neither.
ALTER TABLE app_user ADD COLUMN square_team_member_id VARCHAR(64);
ALTER TABLE app_user ADD COLUMN email VARCHAR(255);

CREATE UNIQUE INDEX app_user_square_member_uq
    ON app_user (square_team_member_id) WHERE square_team_member_id IS NOT NULL;
