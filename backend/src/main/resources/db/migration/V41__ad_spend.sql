-- Manually-entered ad spend per calendar month, for the Marketing Analytics ROI card — there's no
-- Meta/Google Ads API integration, so the owner (or an Ads Manager account) types in what's been
-- spent so far this month. One row per (year, month); re-saving the same month overwrites it.
CREATE TABLE ad_spend (
    id            BIGSERIAL PRIMARY KEY,
    year          INT NOT NULL,
    month         INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    amount_spent  NUMERIC(10,2) NOT NULL DEFAULT 0,
    updated_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ad_spend_year_month_uq UNIQUE (year, month)
);
