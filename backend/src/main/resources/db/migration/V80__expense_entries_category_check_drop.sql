-- expense_entries_category_check (V66) predates expense_categories (V73), which lets the owner
-- create/rename/delete arbitrary categories via a picker UI. Completing a reconciliation for a
-- transaction categorized with an owner-added category (e.g. CONTRACTORS) throws a
-- DataIntegrityViolationException and rolls back the whole completeReconciliation transaction,
-- leaving the import stuck in AWAITING_REVIEW forever. expense_categories.code is now the single
-- source of truth for valid categories; validation moves to the application layer.
ALTER TABLE expense_entries DROP CONSTRAINT expense_entries_category_check;
