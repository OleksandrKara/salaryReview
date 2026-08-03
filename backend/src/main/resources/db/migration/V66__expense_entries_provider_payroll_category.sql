-- Widens expense_entries' category to also accept PROVIDER_PAYROLL: a real bank-statement-derived
-- provider commission payout, mirroring MANAGER_TIME's own treatment (V63). Once a month is covered
-- by a completed statement reconciliation, the provider-commission figure on the Net tab sources
-- exclusively from these linked entries instead of the formula-computed one (OwnerOverviewService).
ALTER TABLE expense_entries DROP CONSTRAINT expense_entries_category_check;
ALTER TABLE expense_entries ADD CONSTRAINT expense_entries_category_check
    CHECK (category IN ('MATERIALS', 'RENT', 'UTILITIES', 'OTHER', 'MANAGER_TIME', 'PROVIDER_PAYROLL'));
