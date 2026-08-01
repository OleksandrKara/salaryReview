-- Salon-wide business expenses (materials/supplies first, other categories as they come up) —
-- same flexible ledger shape as ad_spend_entries (V47): an arbitrary [period_start, period_end]
-- range rather than a fixed calendar month, no uniqueness constraint (a corrected re-entry stays
-- alongside the original for an auditable history), summed with day-overlap proration for any
-- requested report range (see ExpenseResolver). Not landing-page-scoped — unlike ad spend, a
-- materials purchase isn't attributable to one marketing page.
CREATE TABLE expense_entries (
    id            BIGSERIAL PRIMARY KEY,
    category      TEXT NOT NULL CHECK (category IN ('MATERIALS', 'RENT', 'UTILITIES', 'OTHER')),
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL CHECK (period_end >= period_start),
    amount        NUMERIC(10,2) NOT NULL,
    note          TEXT,
    entered_by    VARCHAR(100),
    entered_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_expense_entries_category_period ON expense_entries (category, period_start, period_end);
