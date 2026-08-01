-- Widens expense_entries' category to also accept MANAGER_TIME: a manual backfill of manager
-- labor cost for months before manager_time_entry existed (that table only has real clocked data
-- from July 2026 onward — see V38). OwnerOverviewService prefers the real clocked total when it
-- exists for a month and falls back to entries in this category otherwise (ExpenseService keeps
-- this category out of the generic materials/rent/utilities/other expense total so the two costs
-- aren't double-counted).
ALTER TABLE expense_entries DROP CONSTRAINT expense_entries_category_check;
ALTER TABLE expense_entries ADD CONSTRAINT expense_entries_category_check
    CHECK (category IN ('MATERIALS', 'RENT', 'UTILITIES', 'OTHER', 'MANAGER_TIME'));
