-- Adds DEPOSIT to the set of exclude reasons: a positive-amount transaction (money in) is never
-- an expense by definition, so ExpenseImportService now auto-excludes these on import instead of
-- letting them reach the rule engine or "Needs Review" as if they were a candidate expense.
ALTER TABLE bank_transactions DROP CONSTRAINT bank_transactions_excluded_reason_check;
ALTER TABLE bank_transactions ADD CONSTRAINT bank_transactions_excluded_reason_check
    CHECK (excluded_reason IN
           ('TRANSFER','CREDIT_CARD_PAYMENT','PAYROLL','TAX',
            'OWNER_CONTRIBUTION','CASH_WITHDRAWAL','REFUND','OTHER','DEPOSIT'));
