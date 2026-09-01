## ADDED Requirements

### Requirement: SEO credentials are stored per business, encrypted at rest
The system SHALL persist each business's Search Console service-account credentials, GA4 property
ID, and PageSpeed Insights API key as one `seo_connection` row per business, with the
service-account JSON and API key encrypted using AES-256-GCM under a dedicated master key
independent of any other credential type's encryption key.

#### Scenario: Connecting credentials for a business
- **WHEN** an owner submits valid Search Console/GA4/PageSpeed credentials via
  `PUT /api/owner/settings/seo-connection`
- **THEN** the credentials are stored encrypted, and no plaintext credential value appears in any
  log line or API response afterward

#### Scenario: Two businesses' credentials never mix
- **WHEN** Business A and Business B each have a `seo_connection` row
- **THEN** a scheduled sync or dashboard request for Business A never decrypts or displays
  Business B's credentials or data, and vice versa

### Requirement: SEO monitoring is off by default and gated per business
The system SHALL only run SEO sync jobs and show the SEO dashboard tab for a business whose
`business_feature` row for `seo-monitoring.enabled` is `true`.

#### Scenario: A business without the feature enabled sees no SEO tab
- **WHEN** a business has no `seo_connection` row and/or `seo-monitoring.enabled = false`
- **THEN** the `/owner/marketing/seo` tab does not render for that business's users, and
  `GET /api/owner/marketing/seo/overview` returns 404 for that business

### Requirement: Search Console and PageSpeed data is synced on a schedule, per business, without cross-tenant interference
The system SHALL run a daily Search Console sync and a weekly PageSpeed Insights sync (mobile and
desktop) for every business with `seo-monitoring.enabled = true` and a valid `seo_connection`, each
under that business's own tenant context, and SHALL record the sync's success or failure on the
connection record.

#### Scenario: One business's sync failure doesn't block another's
- **WHEN** Business A's Search Console credentials have been revoked externally and its sync fails
- **THEN** Business B's sync for the same scheduled run still completes successfully, and Business
  A's `seo_connection.last_sync_error` is set to a non-null value visible to its owner

### Requirement: Metrics that cross Google's published Core Web Vitals thresholds are automatically flagged
The system SHALL create a `seo_technical_issue` row when a new `seo_page_snapshot` shows LCP above
2500ms, CLS above 0.1, or INP above 200ms, and SHALL automatically resolve that issue the first
time a later snapshot for the same business/URL/metric falls back to or under the threshold.

#### Scenario: A slow page gets flagged
- **WHEN** a weekly PageSpeed check records LCP of 3200ms for a business's homepage
- **THEN** a `seo_technical_issue` row is created with `issue_type = 'LCP'`, and it appears in that
  business's `/owner/marketing/seo` issues list with a recommendation referencing the 2500ms
  threshold

#### Scenario: A fixed page auto-resolves its issue
- **WHEN** a later PageSpeed check for the same business/URL records LCP at 2000ms
- **THEN** the previously created `seo_technical_issue` row for that metric gets `resolved_at` set,
  without any manual action

### Requirement: High-impression, low-CTR search queries are surfaced as a distinct recommendation
The system SHALL flag a query as a CTR-opportunity issue when its weekly impressions exceed a
configurable floor (default 50) and its CTR is under half the business's trailing-average CTR
across all queries in the same window.

#### Scenario: A high-impression, low-CTR query is surfaced
- **WHEN** a query has 200 impressions in a week and a CTR of 0.5%, while the business's
  trailing-average CTR is 4%
- **THEN** a `seo_technical_issue` row of type `CTR_OPPORTUNITY` is created referencing that query,
  distinct from and clearly labeled apart from the Google-sourced Core Web Vitals issue types
