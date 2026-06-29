-- Per-user preferred language (owner/manager). NULL means "not chosen yet", which the frontend uses
-- to show the one-time setup prompt. English is the default/fallback when content has no Russian
-- version, so we don't backfill — a NULL preference is treated as English everywhere.
ALTER TABLE app_user ADD COLUMN preferred_language VARCHAR(8);
