-- Onboarding order for SOPs: `priority` is a sort key, lower shows first. Existing (and new) rows
-- default high (1000) so an owner can promote the ones a new manager should read first — 1, 2, 3… —
-- while everything unprioritized sorts after them, still by category then title.
ALTER TABLE sops ADD COLUMN priority INTEGER NOT NULL DEFAULT 1000;
