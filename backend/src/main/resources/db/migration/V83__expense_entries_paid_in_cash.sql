-- Lets the owner flag a manually-entered business expense as cash-paid (vs. drawn from the bank),
-- so it can be surfaced separately as "Other Cash Business Expenses" in the P&L — see the P&L
-- redesign. Only meaningful for manual entries; reconciliation-derived expense_entries rows are
-- always bank-sourced by definition and always leave this false.
ALTER TABLE expense_entries ADD COLUMN paid_in_cash BOOLEAN NOT NULL DEFAULT FALSE;
