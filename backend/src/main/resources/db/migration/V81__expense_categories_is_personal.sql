-- Categorized (non-excluded) personal spending was being swept into the generic business-expense
-- bucket by ExpenseService.isGenericCategory, wrongly reducing Net Profit — see the P&L redesign.
-- is_personal lets the owner flag any category (current or future) as personal, excluded from
-- Net Profit but reported separately. Backfills the owner's existing "Personal" category, which
-- was already being used correctly for personal transactions, just miscounted downstream.
ALTER TABLE expense_categories ADD COLUMN is_personal BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE expense_categories SET is_personal = TRUE WHERE code = 'PERSONAL';
