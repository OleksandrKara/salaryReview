## Why

The akluxnails-home SEO push (2026-09-01) connected Search Console, GA4, and PageSpeed Insights
for AK.LUX.NAILS, but the only way to see any of that data today is a hand-run Node script
(`~/seo-monitoring/check.mjs`/`psi.mjs` on the VPS, credentials in flat files — see
[[seo_monitoring_runbook]]) with no history, no trend view, and no automatic flagging when a
metric crosses a known-bad threshold (e.g. LCP above Google's 2.5s "good" cutoff). The owner asked
twice for this to become a real page in the existing owner dashboard instead, with queries running
on a schedule and recommendations surfaced automatically — not something they have to ask an AI
session to go run manually each time.

salaryReview already has exactly the infrastructure this needs: a real multi-tenant foundation
(`business`, `business_feature`, `CurrentBusinessContext`), an existing owner-facing marketing nav
(`/owner/marketing/funnel`, `/ads-report`, `/ltv`, `/contacts`), and a proven encrypted
per-business credential pattern (`SquareConnection` + `SquareCredentialCipher`, AES-256-GCM,
master key from an env var). This change adds SEO monitoring as one more `business_id`-scoped
module following that exact shape — not a new platform, not a rewrite of the manual scripts, an
extension of what's already there.

## What Changes

- **New `seo_connection` table** — one encrypted row per business (Search Console service-account
  JSON, GA4 property ID + measurement ID, PageSpeed API key), mirroring `square_connection`'s
  shape and its own `SeoCredentialCipher` (same AES-256-GCM approach as
  `SquareCredentialCipher`, separate master key/env var — not shared with Square's, so rotating
  one never touches the other).
- **New `seo_search_metrics_snapshot`, `seo_page_snapshot`, `seo_technical_issue` tables** —
  daily/weekly pulled data plus a small, rule-based (not ML) flagging table: an issue row is
  created when a metric crosses a documented Google threshold (LCP > 2.5s, CLS > 0.1, INP > 200ms,
  or a query with high impressions and unusually low CTR) and resolved automatically once the
  metric recovers.
- **Two new scheduled jobs**, following the existing per-business iteration pattern (`business` +
  `seo_connection` join, same shape as the Square sync jobs): a daily Search Console pull and a
  weekly PageSpeed Insights check (mobile + desktop) against each business's homepage.
- **New `/owner/marketing/seo` page**, added as a tab alongside the existing funnel/ads-report/ltv
  ones — trend chart (impressions/clicks/CTR/position), a keyword table, Core Web Vitals cards
  (color-coded against Google's own thresholds), and an active-issues list with the
  recommendation text attached to each.
- **New `/owner/settings/seo` page** (matching the existing `/owner/settings/telegram`,
  `/owner/settings/sms` pattern) — where the owner pastes/uploads the three credentials once per
  business, replacing the manual on-server file drop this runbook currently requires.
- **New `business_feature` key**: `seo-monitoring.enabled` — off by default for every business
  (including AK.LUX.NAILS, until explicitly turned on), matching the existing precedent set by
  `V108__business_feature.sql` for the AI/RAG feature set.

## Non-Goals

- **Not replacing Google's own dashboards.** This surfaces the subset of data useful for a
  day-to-day owner check-in (trend + alerts), not a full Search Console/GA4 clone.
- **Not building a keyword-rank-tracking service beyond what Search Console itself reports.** No
  third-party rank-tracker API, no scraping Google's SERPs — Search Console's own position data is
  free and sufficient (see the original SEO audit's Part 6).
- **Not an ML/anomaly-detection system.** Recommendations are simple, documented threshold rules
  (Google's own published CWV "good" cutoffs, plus one impressions-vs-CTR heuristic) — auditable
  and explainable, not a black-box score.
- **Not Search Console/GA4 OAuth (per-business, owner-driven connection flow) in this change.**
  The owner pastes a service-account JSON they generated themselves (per the runbook), the same
  manual-but-secure model `square_connection` used before Square OAuth existed. A real "connect
  your Google account" OAuth flow is a plausible future change, not this one.
- **Not built for every business by default.** Ships behind `business_feature`, off until an
  owner asks for it — this is a real cost center (Google Cloud project + scheduled job cycles per
  business), not something to silently turn on the way the RAG/AI features accidentally were
  before V108.

## How This Is Verified

- Unit tests for the threshold-flagging logic (exact boundary cases: LCP at exactly 2500ms,
  2501ms, etc.) against Google's documented CWV cutoffs — these numbers must never silently drift
  from what Google's own guidance says.
- Integration test standing up two businesses' `seo_connection` rows and asserting the scheduled
  jobs never mix their data (same cross-tenant isolation bar as the rest of the multi-tenant work).
- Manual check: connect AK.LUX.NAILS' already-real GSC/GA4/PageSpeed credentials (from
  `~/seo-monitoring/credentials/`) through the new settings page, confirm the dashboard shows the
  same numbers the manual scripts already showed, then retire the manual scripts once it matches.
