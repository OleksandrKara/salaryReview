-- phone_number was varchar(32), sized for a real phone number — ColorBoosterWinbackOneOffService
-- (2026-09-05) reuses this column to hold an email address for its one-off campaign (this table
-- has no email column of its own), and a 32-char cap silently rejected any longer address, losing
-- the outcome row entirely for that customer. Widened to text; every other automation here still
-- only ever stores a real phone number, well under any reasonable length.
ALTER TABLE service_lifecycle_reminder_send ALTER COLUMN phone_number TYPE text;
