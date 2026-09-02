Ordered so every phase ships something real and safely-mergeable on its own. Phases 0-4, 6, and 8
are complete and deployed. Phase 5 was cancelled by owner decision (2026-09-02) rather than pay for
an external rank-tracking provider — see the note under Phase 5 below. Phase 7 was redesigned to a
zero-cost scope (same date) once the owner declined DataForSEO/SerpApi for that too.

## 0. Pre-work (small, standalone — done before Phase 1)

- [x] 0.1 Fixed `SearchConsoleClient.queryPerformance`'s per-day loop the same way
      `GoogleAnalyticsClient.dailyTotals` was fixed earlier: batches the whole sync window into
      one `searchAnalytics.query` call via its own date-range support, instead of one call per
      day. `SearchConsoleClientTest` rewritten for the range-based request shape. Shipped in
      PR #539 alongside Phase 1.

## 1. Audit foundation & shared plumbing

- [x] 1.1 Added FCP/TBT thresholds to `CoreWebVitalsThresholds` + `SeoIssueFlaggingService`
      (`seo_page_snapshot` already stored both). **Found and fixed during Phase 8's manual E2E
      pass**: the `seo_technical_issue.issue_type` CHECK constraint (V141) was never widened to
      allow `FCP`/`TBT` — a real poor-FCP/TBT PageSpeed result would have hit a DB constraint
      violation in production, and because `syncPageSpeed` wraps both strategies in one
      `@Transactional` method with no per-strategy savepoint, that violation would have poisoned
      the whole transaction and silently failed the *other* (working) strategy's sync too. Fixed
      via migration V150 + a new `SeoTechnicalIssueRepositoryTest` (real Postgres, not mocked) so
      this class of bug can't recur silently — `SeoIssueFlaggingServiceTest` alone never caught it
      since it mocks the repository. Shipped in PR #544 (Phase 8).
- [x] 1.2 Extracted `FunnelAnalysisController`'s duplicated `language(AppUserPrincipal me)` helper
      into `com.salonreview.ai.LanguageResolver`, used by `RagController`, `FunnelAnalysisController`,
      `SmsActivityController`, and later `SeoAiAdvisorController` (Phase 6). Shipped in PR #539.
- [x] 1.3 Added a `docs/CACHING.md` placeholder noting where a future rank-tracking provider's
      cache/rate-limit pair would live — moot now that Phase 5 is cancelled, left in place as a
      historical note rather than removed.

## 2. Executive Overview + gainers/losers/opportunities

- [x] 2.1-2.5 `SeoChangeDetectionService` (named threshold constants, `SIGNIFICANT_POSITION_MOVE`,
      `SIGNIFICANT_MOVE_MIN_IMPRESSIONS`, striking-distance/high-impressions-low-CTR/growing-
      impressions opportunity detection), 7d/28d/YoY period comparisons on
      `SeoDashboardService.overview()`, Overview UI section (period-stat cards, Biggest
      wins/losses, Opportunities). Found and fixed a small pre-existing `positionDeltaLabel` bug
      along the way (a near-zero delta rendered as a confusing "-0.0"). Shipped in PR #540.

## 3. Page performance + query→page mapping/cannibalization

- [x] 3.1-3.4 `SeoPageAnalysisService` (winning/losing pages, underperforming pages, content
      opportunities rank 5-20, cannibalization detection). Extracted `SeoMetricsAggregate`/
      `SeoWindowSplit` once a third independent copy of the same aggregation formula was about to
      be written. Shipped in PR #541.

## 4. Local SEO foundation (schema only — Phase 5's own rank checks never arrived, see below)

- [x] 4.1-4.3 Migration V148 + `SeoTrackedKeyword` entity (business_id, keyword, target_url,
      location, device, active); add/remove endpoints; "Local SEO keywords" card. **Found and
      fixed a real architectural gap during manual verification**: the whole dashboard (including
      this card) was hidden behind the "no credentials connected" empty state, but the keyword
      watchlist is meant to be buildable before connecting Search Console/GA4/PageSpeed —
      restructured that branch to render the keyword card alongside the connect-prompt. Shipped
      in PR #542.
      This list still has no real rank-check data behind it (Phase 5 cancelled) and stays exactly
      as useful as originally scoped: a keyword watchlist with location/device, ready to wire up
      to a rank-check provider later if the owner ever reconsiders.

## 5. Keyword rank tracking — CANCELLED (2026-09-02)

**Not built.** Design.md D2 recommended DataForSEO as the real-SERP-rank provider; the owner
declined to pay for it. Options discussed and explicitly rejected:
- DataForSEO / SerpApi (paid, pay-per-lookup) — declined on cost.
- Self-hosted Google-search scraping — not implemented: violates Google's ToS, gets IP-blocked/
  CAPTCHA'd quickly, and the proxy infrastructure needed to make it reliable tends to cost money
  anyway, defeating the "free" premise.
- Google Custom Search JSON API's free tier (100 queries/day) — technically free and *could* work
  as an experimental, best-effort rank check, but Google doesn't officially support or guarantee
  this API's result order matches real organic search, so it was explicitly declined too rather
  than ship a "rank" number the owner might trust as real.

**What stands in its place**: Search Console's own average position (already fully shipped —
`seo_search_metrics_snapshot`, the Search trend chart, `topQueries`, and the gainers/losers/
opportunities detection in Phase 2-3) is the free, already-integrated signal, with the existing UI
explicitly labeling it as an average, not a single tracked SERP rank. `seo_tracked_keyword`
(Phase 4) stays in place as a ready-to-use watchlist if a provider decision is ever revisited.

## 6. SEO AI Advisor

- [x] 6.1-6.7 `SeoContextBuilderService`/`SeoAnalysisSnapshot` (reuses Phase 2-3's already-ranked/
      capped lists directly rather than a second budget system), `SeoAiAdvisorService`/
      `SeoAnalysisResult`/`SeoAdvisorPrompts` (mirrors `FunnelAnalysisService`'s architecture
      exactly), `seo_analysis` (V149), `SeoAiAdvisorController`, `MeController`'s
      `aiSeoAdvisorEnabled` flag, the "Analyze SEO" card. Shipped in PR #543 — shipped dark
      (`AI_SEO_ADVISOR_ENABLED=false`); **turned on in production 2026-09-02** (env var +
      `business_feature` row for business 1, rolling-restarted both backend replicas, verified
      healthy).

## 7. Competitor intelligence — zero-cost scope (redesigned 2026-09-02)

Originally scoped around the same paid rank-tracking provider as Phase 5 for keyword-overlap
comparison — the owner declined that cost here too. Redesigned to what's genuinely free:

- [ ] 7.1 Migration `V151__seo_competitor.sql`: `seo_competitor` (business_id, name, website,
      location, notes, active, gbp_rating, gbp_review_count, gbp_updated_at — the last three are
      owner-entered, never auto-synced, since there's no free API for a competitor's own GBP data)
      + `seo_competitor_page_snapshot` (competitor_id, date, strategy, performance_score, lcp_ms,
      cls, fcp_ms, tbt_ms — same shape as `seo_page_snapshot`, since PageSpeed Insights already
      works on any public URL for free, not just the owner's own site).
- [ ] 7.2 Owner-facing competitor CRUD (~3 competitors: add/edit/deactivate/remove, plus editing
      the manually-entered GBP rating/review count) — same `business_feature`-gated, owner-only
      pattern as tracked-keywords.
- [ ] 7.3 Extend the existing weekly `SeoPageSpeedSyncScheduler`/`SeoSyncService.syncPageSpeed` to
      also run PageSpeed Insights against each active competitor's website (same weekly cadence,
      reusing the existing quota-conscious reasoning — PSI's quota is already the tight constraint,
      per design.md's own Risks section, so bundling competitor checks into the same existing
      weekly job rather than a new schedule keeps that quota math the same).
- [ ] 7.4 Competitors sub-view (frontend): comparison table/cards — our own latest CWV next to each
      competitor's, GBP rating/reviews shown with an explicit "owner-entered" label, and keyword-
      overlap/backlink comparison explicitly shown as "not available without a paid SEO tool"
      rather than omitted silently.
- [ ] 7.5 Feed the competitor comparison summary into `SeoContextBuilderService`/`SeoAnalysisSnapshot`
      once built, so the AI Advisor's recommendations can reference a real competitor CWV gap when
      one exists.

## 8. Alerts + final IA polish

- [x] 8.1 `AlertsCard` — single most significant item from gainers/losers/period-comparison/
      winning-pages/technical-issues/opportunities, dismissible-per-session one-liners at the top
      of Overview. No new backend endpoint/table (design.md D11).
- [~] 8.2 Full 6-tab IA reshuffle (design.md D10) — **deferred, not done**. The single-page layout
      has been manually verified overflow-free on mobile/desktop after every phase, including the
      final comprehensive pass with every phase's data seeded simultaneously (Phase 8). Given
      Phase 5 is now cancelled and Phase 7 is a smaller, cost-free scope than originally planned,
      the page's final content size is smaller than the design doc assumed when it recommended a
      tab reshuffle — revisit only if Phase 7's Competitors section actually makes the page feel
      too long in practice, rather than reshuffling preemptively.
- [x] 8.3-8.4 Comprehensive manual E2E pass (throwaway env, every phase's data seeded at once) +
      regression check (pre-existing sync/pin/unpin flows untouched). Shipped in PR #544.
