-- SEO competitors per site (2026-10-14, Notion #52): the PMU business has the studio and the
-- academy on one domain, with different competitors (studios vs PMU schools). NULL = the studio /
-- the business's only site, 'academy' = the PMU Academy (see SeoSite).
ALTER TABLE seo_competitor ADD COLUMN site TEXT;
