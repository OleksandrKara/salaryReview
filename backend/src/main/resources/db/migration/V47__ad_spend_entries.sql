-- Replaces ad_spend (one blended figure per calendar month) with a per-landing-page, per-arbitrary-
-- period ledger: the owner can now enter a real figure for any range (a week, a full month, "1st of
-- this month through today") on whichever page it was actually spent on. Any report period's spend
-- is computed by summing entries overlapping it, prorated by calendar-day overlap for entries that
-- only partially overlap (see MarketingAnalyticsService/AdSpendResolver) — no uniqueness constraint,
-- since a corrected re-entry should stay alongside the original for an auditable history rather than
-- silently overwrite it.
CREATE TABLE ad_spend_entries (
    id                 BIGSERIAL PRIMARY KEY,
    landing_page_slug  TEXT NOT NULL,
    period_start       DATE NOT NULL,
    period_end         DATE NOT NULL CHECK (period_end >= period_start),
    amount_spent       NUMERIC(10,2) NOT NULL,
    entered_by         VARCHAR(100),
    entered_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ad_spend_entries_page_period ON ad_spend_entries (landing_page_slug, period_start, period_end);

-- Every existing monthly figure becomes one whole-month 'mani' entry — the only page with real ad
-- spend history today (see openspec/changes/ads-report-consolidation/design.md D5).
INSERT INTO ad_spend_entries (landing_page_slug, period_start, period_end, amount_spent, entered_by, entered_at)
SELECT
    'mani',
    make_date(year, month, 1),
    (make_date(year, month, 1) + INTERVAL '1 month' - INTERVAL '1 day')::date,
    amount_spent,
    updated_by,
    updated_at
FROM ad_spend;

DROP TABLE ad_spend;
