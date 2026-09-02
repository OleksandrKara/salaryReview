## MODIFIED Requirements

### Requirement: The SEO overview surfaces period-over-period change, not just point-in-time totals
The system SHALL show organic clicks, impressions, CTR, and average position alongside their
change versus the previous 7-day and previous 28-day period, and versus the same period one year
prior once at least 370 days of `seo_search_metrics_snapshot` history exist for the business.

This supersedes the previous version of this requirement, which displayed only the current
28-day totals with no comparison baseline.

#### Scenario: Owner opens the SEO Overview
- **WHEN** an owner with `seo-monitoring.enabled` on views the Overview sub-view
- **THEN** every headline metric shows its current value and a labeled delta against the previous
  7-day and 28-day period, with no delta shown as a raw metric alone

#### Scenario: Insufficient history for a year-over-year comparison
- **WHEN** a business has fewer than 370 days of `seo_search_metrics_snapshot` history
- **THEN** the year-over-year comparison is omitted entirely rather than shown against a partial
  or misleading baseline

### Requirement: Significant SEO changes are automatically surfaced as gainers and losers
The system SHALL identify and list keyword position moves, page impression/click changes, and CTR
changes that cross a named significance threshold, and SHALL NOT list changes below that threshold.

This supersedes the previous version of this requirement, which had no gainers/losers detection
at all.

#### Scenario: A tracked query's position improves significantly
- **WHEN** a query's impressions-weighted average position improves by at least the configured
  significant-move threshold between the earlier and later half of the evaluated window
- **THEN** it appears in the "biggest wins" list with its before/after position and the underlying
  evidence (impressions, clicks)

#### Scenario: An insignificant fluctuation is not surfaced
- **WHEN** a query's position moves by less than the configured threshold (e.g. #10 → #9)
- **THEN** it does not appear in either the wins or losses list

### Requirement: Keyword opportunities are automatically detected from existing Search Console data
The system SHALL identify striking-distance keywords (position roughly #4-20 with meaningful
impressions), high-impression/low-CTR pages, and queries with growing impressions, using only data
already present in `seo_search_metrics_snapshot`.

This supersedes the previous version of this requirement, which had no opportunity detection.

#### Scenario: A striking-distance keyword is detected
- **WHEN** a tracked or auto-suggested query's current-period average position falls within the
  configured striking-distance band and its impressions meet the configured minimum
- **THEN** it appears in the Keywords/Pages opportunities list with its position, impressions, and
  CTR as supporting evidence

### Requirement: Page-level performance is presented as a dedicated view, distinguishing winning, losing, and underperforming pages
The system SHALL group existing `seo_search_metrics_snapshot` rows by page and classify each
tracked page as winning, losing, or underperforming (high impressions, weak CTR or position)
relative to its own prior-period values.

This supersedes the previous version of this requirement, which had no page-level view — only a
flat top-queries table.

#### Scenario: A page loses visibility
- **WHEN** a page's clicks or impressions drop by at least the configured significant-change
  threshold versus the prior period
- **THEN** it is listed as a losing page with its top queries and the size of the drop

### Requirement: Potential keyword cannibalization is flagged, never asserted
The system SHALL detect when more than one page receives a meaningful share of impressions or
clicks for the same query within the evaluated window, and SHALL present this as a labeled
"potential optimization opportunity," not as a confirmed problem.

This supersedes the previous version of this requirement, which had no query→page mapping
analysis.

#### Scenario: Two pages compete for the same query
- **WHEN** two or more distinct pages each receive a meaningful share of impressions for the same
  query in the current window
- **THEN** the query is flagged with all competing pages listed and labeled as a potential
  optimization opportunity, without asserting which page (if any) is wrong

### Requirement: Technical SEO issue detection evaluates Core Web Vitals thresholds already captured in stored data
The system SHALL evaluate First Contentful Paint and Total Blocking Time against Google's
published thresholds, in addition to the existing Largest Contentful Paint and Cumulative Layout
Shift evaluation, using only `seo_page_snapshot` fields already populated by the existing
PageSpeed sync.

This supersedes the previous version of this requirement, which evaluated only LCP and CLS despite
`seo_page_snapshot` already storing FCP and TBT.

#### Scenario: A page's FCP exceeds Google's threshold
- **WHEN** a `seo_page_snapshot` row's `fcpMs` exceeds Google's published "needs improvement" or
  "poor" cutoff
- **THEN** an open `seo_technical_issue` row is created or updated for that page/strategy, and
  auto-resolved once a subsequent snapshot falls back under the "good" threshold

#### Scenario: Data the underlying APIs cannot provide is not fabricated
- **WHEN** a technical SEO check (e.g. sitemap status, broken links, structured data validity)
  requires data Search Console and PageSpeed Insights do not expose
- **THEN** the system does not display a value for it, and does not imply the check was performed
