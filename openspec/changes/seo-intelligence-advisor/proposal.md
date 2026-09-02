## Why

`/owner/marketing/seo` (shipped in `seo-monitoring-dashboard`, PRs #524-#528, extended with GA4
traffic + a tracked-query watchlist earlier in this same work stream) already gives AK.LUX.NAILS'
owner a read-only mirror of Search Console, GA4, and PageSpeed data: a 28-day trend chart, a
CTR/position stat row, Core Web Vitals cards, an auto-flagged issue list, a pinned/auto-suggested
"main queries" table, and a manual "Sync now" button. That is a **reporting** surface — it shows
numbers. It does not yet answer the two questions the owner actually has: *"what changed and does
it matter,"* and *"what should I do about it."* Today the owner (or this assistant, on their
behalf) has to read the raw Search Console table by eye every week and manually reason about it —
exactly the manual analysis this proposal exists to remove.

AK.LUX.NAILS is a single-location, appointment-based, high-ticket (premium, no-acrylic, ~2hr
appointments) local business with a realistic ~20-minute customer radius. Nothing in the existing
dashboard treats location as a first-class dimension, tracks a real SERP rank for a curated
keyword list (Search Console's "average position" is a *query-level statistical aggregate across
however Google chose to rank the page for that query, blended across devices/personalization* —
not the same thing as "where do we rank for 'russian manicure san diego' right now," and the two
are conflated by anyone reading the existing average-position stat as a rank), or looks at a
competitor. There is also no AI-assisted interpretation anywhere in the SEO tab, despite this app
already having a proven, tested pattern for exactly this shape of feature
(`com.salonreview.ai.FunnelAnalysisService` — owner-triggered, language-aware, cost-controlled via
a snapshot-fingerprint cache, structured-output, persisted history — see design.md D7).

This proposal turns the existing SEO tab from a metrics mirror into a decision-support system,
reusing every existing integration, credential, sync job, and UI convention it can, and only adding
new surface area (keyword rank tracking, competitor config, an AI advisor) where the current stack
genuinely has no equivalent.

## What Changes

- **Executive Overview** — reorders the existing tab's top section into a mobile-first "answer the
  question in 10 seconds" summary: organic clicks/impressions/CTR/avg-position with real
  period-over-period deltas (7d/28d, YoY once ≥370 days of `seo_search_metrics_snapshot` history
  exists), the single most important AI recommendation (once Feature 10 ships), and a health
  status derived from open `seo_technical_issue` rows — no new vanity metric that doesn't already
  answer a stated question.
- **Keyword rank tracking** (new capability, `seo-keyword-tracking`) — owner-curated list of
  10-50 keywords, each with an explicit tracked **location** (Downtown San Diego / 92101 by
  default, editable), checked against a real SERP via a new external provider (see design.md D2),
  stored as daily `seo_rank_snapshot` rows, rendered as a trend chart + 🟢/🔴/⚪ status, clearly
  labeled as a *tracked SERP position* distinct from Search Console's average position (which stays
  visible, unchanged, on the existing `seo_search_metrics_snapshot`-backed pages).
- **Gainers & losers + opportunities** — a change-detection pass over the existing
  `seo_search_metrics_snapshot`/new `seo_rank_snapshot` data: configurable-threshold significant
  movers, striking-distance keywords (rank #4-20 + meaningful impressions), high-impression/low-CTR
  pages, and unclaimed/growing-impression queries — all derived from data already being
  synced today plus the new rank feed, no new external dependency.
- **Page performance + query→page mapping** — a page-level view over the existing per-(query,page)
  `seo_search_metrics_snapshot` rows already carrying both dimensions; surfaces winning/losing/
  underperforming pages and flags likely keyword cannibalization (>1 page meaningfully ranking for
  the same tracked query) as "potential optimization opportunity," never an assertion.
  Query-to-page mapping capability itself is not new — the *presentation* of it is.
- **Technical SEO widening** — `SeoIssueFlaggingService` currently evaluates LCP/CLS only, though
  `seo_page_snapshot` already stores FCP/TBT; add those two thresholds. Explicitly does **not**
  add broken-link/indexation/sitemap/schema checks — Search Console and PageSpeed APIs don't
  provide first-class data for those (see design.md D9); flagged as a separate, not-yet-justified
  crawler requirement.
- **Competitor intelligence** (new capability, `seo-competitor-intelligence`) — an owner-editable
  list of ~3 competitors (name/site/GBP info/notes/active flag, no hardcoding), compared against
  our own data on whatever dimensions the chosen new provider (D2) and public GBP data can
  actually supply — every competitor metric explicitly labeled with its source and confidence,
  never presented as Google's own data.
- **SEO AI Advisor** (new capability, `seo-ai-advisor`) — an "Analyze SEO" button reusing the
  `FunnelAnalysisService` architecture end-to-end: a new context-aggregation pipeline (raw data →
  significance filtering → ranked/prioritized structured snapshot, not raw rows) feeding a
  structured-output Claude call, persisted as new, never-overwritten `seo_analysis` rows (mirroring
  `funnel_analysis`'s shape), language-aware via the same `AppUser.preferredLanguage` mechanism,
  cost-controlled via the same fingerprint-cache pattern (never called automatically on page load).
- **Alerts** — threshold-based notices (rank drop, traffic drop/spike, new technical issue,
  striking-distance entry) surfaced in the existing Overview, not a new notification channel.
- **IA reshuffle** — the single SEO page becomes 6 sub-views (Overview / Keywords / Pages /
  Technical / Competitors / Advisor) behind an in-page mobile-first tab strip, folding the
  originally-suggested separate "Opportunities" and "History" screens into Keywords/Pages and
  Advisor respectively to keep navigation depth manageable (design.md D11).

## Non-Goals

- **Not replacing any existing integration.** Search Console, GA4, and PageSpeed clients, sync
  jobs, credential storage, and the existing trend/CWV/issue UI are reused as-is except the two
  additive changes called out above (FCP/TBT thresholds; an optional later widening of
  `SearchConsoleClient`'s dimension set, tracked as a fast-follow, not part of this change).
- **Not building an in-house web crawler.** Sitemap/robots/broken-link/duplicate-content/schema
  checks are out of scope until a real need is justified — Search Console and PageSpeed cannot
  supply this data today (design.md D9).
- **Not scraping Google SERPs in-house.** Real rank checks go through a paid, ToS-compliant
  third-party API (design.md D2) — this app will not run its own scraper.
- **Not adding automatic/scheduled AI analysis.** Every existing Claude-powered feature in this app
  is owner/manager-triggered, never cron-driven; the AI Advisor follows the same rule (design.md
  D7). Rank-tracking and its own change-detection *are* scheduled — those are data syncs, not LLM
  calls.
- **Not presenting third-party/competitor data as Google data.** Every metric on every screen
  carries a source label; this is a hard requirement, not a nice-to-have (design.md D10).
- **Not building two separate mobile/desktop UIs.** One responsive implementation, mobile-first
  breakpoints (design.md D11).
- **Not implementing anything in this change.** This document set is the audit + plan only, per
  the explicit stop condition this work was requested under. Phase 0 (below) begins only once the
  owner approves proceeding.

## How This Is Verified

This change is docs-only — nothing to test yet. Once approved, each phase in `tasks.md` carries
its own verification (unit tests for scoring/threshold/aggregation logic, integration tests against
the new rank-tracking provider client via a mocked HTTP layer exactly like
`SearchConsoleClientTest`/`GoogleAnalyticsClientTest`, a `SchedulerLockAnnotationsTest`-covered new
scheduled job, an `AnalysisFailedException`→502 contract test mirroring
`FunnelAnalysisServiceTest`, and a real manual E2E pass in a throwaway environment — the same
`frontend/e2e/README.md`-documented workflow used to verify the GA4 chart and tracked-query
features earlier in this work stream, including a mobile-viewport overflow check).
