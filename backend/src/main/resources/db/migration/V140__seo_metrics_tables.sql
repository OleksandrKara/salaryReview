-- seo-monitoring-dashboard Phase 1 (design.md D2) — two purpose-shaped tables rather than one
-- generic metric_key/metric_value table, since search-metrics rows and page-snapshot rows have
-- genuinely different dimensions (query+page+date vs. url+strategy+date).

-- One row per (business, date, query, page) from Search Console's searchAnalytics.query.
CREATE TABLE seo_search_metrics_snapshot (
    id           BIGSERIAL PRIMARY KEY,
    business_id  BIGINT NOT NULL REFERENCES business(id),
    date         DATE NOT NULL,
    query        TEXT NOT NULL,
    page         TEXT,
    clicks       INTEGER NOT NULL,
    impressions  INTEGER NOT NULL,
    ctr          NUMERIC(6,4) NOT NULL,
    position     NUMERIC(6,2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, date, query, page)
);

CREATE INDEX idx_seo_search_metrics_business_date
    ON seo_search_metrics_snapshot (business_id, date DESC);

-- One row per (business, date, url, strategy) from a PageSpeed Insights run.
CREATE TABLE seo_page_snapshot (
    id                 BIGSERIAL PRIMARY KEY,
    business_id        BIGINT NOT NULL REFERENCES business(id),
    date               DATE NOT NULL,
    url                TEXT NOT NULL,
    strategy           TEXT NOT NULL CHECK (strategy IN ('MOBILE', 'DESKTOP')),
    performance_score  INTEGER NOT NULL,
    lcp_ms             INTEGER,
    cls                NUMERIC(6,4),
    fcp_ms             INTEGER,
    tbt_ms             INTEGER,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, date, url, strategy)
);

CREATE INDEX idx_seo_page_snapshot_business_date
    ON seo_page_snapshot (business_id, date DESC);
