CREATE TABLE expense_categories (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    label       TEXT NOT NULL,
    protected   BOOLEAN NOT NULL DEFAULT false,
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed the categories already in use (openspec change expense-import-reconciliation, D8/D11/D12).
-- MANAGER_TIME and PROVIDER_PAYROLL are protected: their codes are hardcoded backend constants
-- (ExpenseEntry.CATEGORY_MANAGER_TIME/CATEGORY_PROVIDER_PAYROLL) with special net-revenue handling
-- and must never be deleted or renamed by code — only their display label may change.
INSERT INTO expense_categories (code, label, protected, sort_order) VALUES
    ('MATERIALS', 'Materials', false, 10),
    ('RENT', 'Rent', false, 20),
    ('UTILITIES', 'Utilities', false, 30),
    ('OTHER', 'Other', false, 40),
    ('MANAGER_TIME', 'Manager time', true, 50),
    ('PROVIDER_PAYROLL', 'Provider payroll', true, 60);
