## Context

Highest Flyway migration is `V138` (`V138__funnel_analysis_variant_id.sql`) — next new migration
is `V139`. The multi-tenant foundation from `multi-tenant-salon-platform` is substantially live,
not just proposed: `business`, `business_membership`, `business_feature`, `CurrentBusinessContext`
+ `CurrentBusinessContextFilter`, and `SquareConnection`/`SquareCredentialCipher` all exist in the
codebase today. This change is additive on top of that real foundation, not a redesign of it.

## Decisions

### D1 — Mirror `SquareConnection`/`SquareCredentialCipher` exactly, as a separate pair of classes

**Decision:** new `SeoConnection` entity + `SeoCredentialCipher` component, same AES-256-GCM
shape as `SquareCredentialCipher`, own master key (`seo.credentials.master-key` /
`SEO_CREDENTIALS_MASTER_KEY` env var) — not the same key or class as Square's.

**Why:** `SquareCredentialCipher`'s encrypt/decrypt methods are already credential-agnostic (plain
string AES-GCM), so technically reusing the same class instance would work. Rejected anyway: the
two credential sets (Square access tokens vs. Google service-account JSON/API keys) should be
independently rotatable — sharing one master key means rotating it for a Square incident also
forces re-encrypting every business's Google credentials, and vice versa. A second small class
with its own key is cheap and keeps the blast radius of any one key rotation contained to the
credential type it actually concerns. This does **not** touch or rename the existing
`SquareCredentialCipher` — matches the "don't refactor unrelated code" convention.

### D2 — Three data tables, not one wide table

`seo_search_metrics_snapshot` (Search Console: date, query, page, clicks, impressions, ctr,
position), `seo_page_snapshot` (PageSpeed: date, url, strategy, performance_score, lcp_ms, cls,
fcp_ms, tbt_ms), `seo_technical_issue` (issue_type, detail, severity, first_seen_at, resolved_at).
Kept separate rather than one generic `metric_key/metric_value` table because each has genuinely
different dimensions (a search-metrics row is keyed by query+page+date; a page-snapshot row by
url+strategy+date) — a single generic table would need nullable columns for whichever dimensions
don't apply to a given row type, which is worse for indexing and query clarity than three small,
correctly-shaped tables. All three carry `business_id` directly (no FK-inheritance path exists for
data that originates outside this app entirely).

### D3 — Threshold-based flagging, sourced from Google's own published cutoffs, not invented numbers

`seo_technical_issue` rows are created by the same scheduled job that ingests a `seo_page_snapshot`
or `seo_search_metrics_snapshot` row, checking against a small, named constant set:
- LCP: good ≤ 2500ms, needs-improvement ≤ 4000ms, poor > 4000ms
- CLS: good ≤ 0.1, needs-improvement ≤ 0.25, poor > 0.25
- INP: good ≤ 200ms, needs-improvement ≤ 500ms, poor > 500ms

(all three are Google's own published Core Web Vitals thresholds, current as of the 2026-09-01
akluxnails-home audit — cite the source, don't re-derive these numbers later without checking
they haven't changed). One additional heuristic, not a Google-published number and clearly
labeled as such in the UI: a query with impressions above a configurable floor (default 50/week)
and CTR under half the site's own trailing-average CTR gets flagged as "high impressions, low
CTR — review this page's title/meta description," since that's a real, actionable signal distinct
from raw CWV thresholds.

An issue auto-resolves (sets `resolved_at`) the first time a later snapshot for the same
business+metric+URL falls back under the "good" threshold — no manual dismiss needed for the
common case, though a manual dismiss/snooze is still useful for the CTR heuristic (which is
advisory, not a hard pass/fail) — see tasks.md for whether that ships in v1.

### D4 — Scheduled jobs iterate businesses the same way the Square sync jobs already do

Daily Search Console pull, weekly PageSpeed check — both query `business JOIN seo_connection`
(only businesses that have actually connected credentials) `JOIN business_feature WHERE
feature_key = 'seo-monitoring.enabled' AND enabled`, then loop per business under
`CurrentBusinessContext.runAs(businessId, …)`, exactly the pattern already established for
`RevenueSnapshotScheduler` and the Square sync jobs post-multi-tenant-migration. ShedLock keys get
a `-business-{id}` suffix, same convention.

### D5 — `business_feature` gate, not always-on

New key `seo-monitoring.enabled`, off by default for every business (including a backfill row for
Business A set to `false` explicitly, not left absent — absent and `false` are behaviorally
identical today per `business_feature`'s own null-means-disabled convention, but an explicit row
makes intent legible rather than relying on that convention silently). The owner turns it on
per-business only after connecting real credentials through the new settings page — this avoids
the exact mistake `V108`'s own migration comment describes happening with the AI/RAG features
(AK PMU silently inheriting a feature nobody asked for or priced).

### D6 — Frontend: new tab in the existing `MarketingTabs`, new page under `owner/settings`

`/owner/marketing/seo` joins the existing `MarketingTabs.tsx` tab set (funnel/ads-report/ltv/
contacts) rather than a new top-level nav item — matches the existing information architecture,
and the feature-gate (D5) means the tab simply doesn't render for a business that hasn't enabled
it, consistent with how optional features are already hidden elsewhere in this app. Credential
entry lives at `/owner/settings/seo`, mirroring `/owner/settings/telegram` and
`/owner/settings/sms`'s existing pattern (paste-in config, not an OAuth redirect — see proposal.md
Non-Goals). The tab does **not** need `MarketingTabs`' `?slug=` landing-page selector — that
selector picks which marketing *funnel* (mani/home/pmu) to view, a different dimension from SEO
(which is scoped to the business's one primary organic-search domain, tracked via a single
`seo_connection` per business, not per landing page — see D2's schema; not building
multi-property-per-business is intentional, no evidence any business needs it yet).

### D7 — UX/UI: reuse existing components and conventions exactly, don't invent a new visual language

Surveyed the real patterns already in `frontend/app/owner/` before designing this page, rather
than freehanding a new look:

- **Charts**: `recharts` is already a real dependency (`package.json`, used by
  `NewReturningChart.tsx`/`AdsReportView.tsx`) — the trend chart (impressions/clicks/CTR/position
  over time) uses `recharts`' `ResponsiveContainer` + `LineChart`, not a new charting approach and
  not the hand-rolled bar-chart style `RevenueChart.tsx` uses (that one predates `recharts` being
  added and is revenue-specific; new work reaches for the library already in `package.json`).
- **Mobile pattern for secondary detail**: `RevenueChart.tsx`'s own convention — hide
  Y-axis labels and month-over-month deltas below `sm:` rather than cramming them into a narrow
  viewport (`hidden sm:block`) — applied the same way to the CWV detail sub-metrics (FCP/TBT/Speed
  Index shown only ≥`sm:`; LCP/CLS/INP, the three that actually gate pass/fail, always visible).
- **Mobile pattern for the tab strip itself**: `MarketingTabs.tsx` already uses
  `overflow-x-auto` (horizontal scroll) rather than wrapping onto a second line — the new `SEO` tab
  is simply one more entry in that same scrollable strip, no new nav pattern introduced.
- **Semantic color**: this app already uses `emerald`/`rose` for positive/negative deltas
  (`RevenueChart.tsx`'s MoM row) — the CWV pass/fail states extend that same idiom rather than
  introducing new colors: `emerald` (good), `amber` (needs-improvement), `rose` (poor) — matching
  Google's own three-tier CWV color convention (green/amber/red) onto colors this codebase already
  uses for good/bad, so nothing here is a novel palette decision.
- **Loading/pending states**: reuse the existing `Spinner` component and `useTransition` pattern
  (`MarketingTabs.tsx`) for the manual "sync now" button and any period/filter change — not a new
  loading-state mechanism.
- **Empty/not-connected state**: before any `seo_connection` exists for a business, the tab (once
  a manager/owner has turned the feature on but not yet connected credentials) shows a single
  centered card linking to `/owner/settings/seo` — same "how to get started" empty-state shape
  already used for other optional features in this app, not a bespoke onboarding flow.
- **Numbers**: currency/date formatting follows the exact conventions already established
  (`toLocaleString('en-US', {...})` for numbers, `fmtLastSyncedAt`'s absolute-time-not-relative
  convention for "last synced" — a page left open all day must not show a stale "5 minutes ago").

Nothing in this page introduces a new UI pattern, a new dependency, or a new responsive
breakpoint strategy — every decision above cites the existing file it was taken from.

## Risks

- **Google API quota/cost per business.** Search Console and GA4 Data API are free within
  generous quotas for this traffic volume; PageSpeed Insights has a stricter default quota (already
  hit once during manual testing on 2026-09-01) — the weekly-not-daily cadence for PSI checks
  specifically accounts for this.
- **Credential rotation UX.** If a business's service-account key is rotated/revoked on the Google
  side outside this app, the scheduled job will start failing silently unless it surfaces that
  failure somewhere the owner sees — the sync job must write a visible "last sync failed" state per
  business, not just log-and-skip (same class of gap this app's own Square sync already solved with
  its "last successful sync" UI indicator — reuse that pattern, don't reinvent it).
- **Threshold drift.** Google has changed CWV thresholds before (INP replaced FID in 2024) — the
  constants in D3 need a comment pointing at the source and a periodic manual check, not an
  assumption they're permanent.

## Open Questions

1. Should the CTR-heuristic issue type support a manual "snooze"/dismiss, or is auto-resolve-only
   sufficient for v1? (Flagged in D3 — doesn't block starting Phase 1.)
2. Which URL(s) does the weekly PageSpeed check cover — homepage only, or a configurable list (e.g.
   the akluxnails-home blog posts too)? Homepage-only is the safer v1 scope; expanding later is
   additive, not a redesign.
