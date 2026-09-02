Ordered so every phase ships something real and safely-mergeable on its own — no phase depends on
a later one's external-provider decision (Open Question 1). Phases 1-4 need zero new cost or new
credentials; Phases 5-7 are the ones gated on the owner's go-ahead for a paid provider and a second
AI feature.

## 0. Pre-work (small, standalone — do before or alongside Phase 1)

- [ ] 0.1 Fix `SearchConsoleClient.queryPerformance`'s per-day loop the same way
      `GoogleAnalyticsClient.dailyTotals` was fixed this session: batch the whole sync window into
      one `searchAnalytics.query` call using its own date-range support, instead of one call per
      day. Update `SearchConsoleClientTest` accordingly. (design.md D12 — this is not new feature
      work, it's closing the same latent-504 risk class before this change adds more sync load to
      the same request path.)

## 1. Audit foundation & shared plumbing

- [ ] 1.1 Add FCP/TBT thresholds to `CoreWebVitalsThresholds` + `SeoIssueFlaggingService`
      (`seo_page_snapshot` already stores both; this is evaluation-only, no migration).
- [ ] 1.2 Extract `FunnelAnalysisController`'s duplicated `language(AppUserPrincipal me)` helper
      into a small shared utility (e.g. `com.salonreview.ai.LanguageResolver`) used by
      `RagController`, `FunnelAnalysisController`, and the new `SeoAiAdvisorController` (Phase 6) —
      a mechanical extraction of already-identical code, not a behavior change. Update the two
      existing call sites in the same PR so there's never a window with 3 copies.
- [ ] 1.3 Add `docs/CACHING.md` entry (or a stub, to be filled in Phase 5) marking where the new
      rank-tracking provider's semaphore+cache pair will live, mirroring `SquareClient`'s existing
      entry format.

## 2. Executive Overview + gainers/losers/opportunities (no new external dependency)

- [ ] 2.1 New `SeoChangeDetectionService` (design.md D4): named threshold constants, unit-tested
      pure logic — significant position/impression/click movers, striking-distance detection,
      reuses the existing CTR-opportunity heuristic's shape.
- [ ] 2.2 Period-over-period deltas (7d/28d, YoY once enough history exists) computed in
      `SeoDashboardService` from existing `seo_search_metrics_snapshot`/`seo_analytics_snapshot`
      rows — no new table.
- [ ] 2.3 `SeoDashboardController`: extend `GET overview` (or add a dedicated endpoint if the
      payload grows too large for one response) with the gainers/losers/opportunities lists.
- [ ] 2.4 Frontend: reshuffle `SeoDashboardView.tsx`'s top section into the Overview sub-view
      (design.md D10's 6-tab IA) — 10-second-scan layout, deltas, wins/losses cards. Reuse existing
      recharts/emerald-rose/mobile-hide conventions; verify no regression of the `w-full min-w-0`
      overflow fix.
- [ ] 2.5 Unit tests for `SeoChangeDetectionService`'s threshold logic (mirroring
      `SeoIssueFlaggingServiceTest`'s mocked-repository, no-Spring-context style).

## 3. Page performance + query→page mapping/cannibalization

- [ ] 3.1 New page-level aggregation in `SeoDashboardService` (or a small dedicated service if
      logic grows): winning/losing/underperforming pages, content opportunities (rank 5-20 +
      meaningful impressions) — all from existing `seo_search_metrics_snapshot` rows.
- [ ] 3.2 Cannibalization detection (design.md D5): group by query, flag >1 page with meaningful
      share of impressions/clicks, label "potential optimization opportunity."
- [ ] 3.3 New Pages sub-view (frontend) — per-page table/cards, top queries per page, trend,
      cannibalization flags. Mobile-first (cards, not a wide table, below the existing
      `overflow-x-auto` fallback breakpoint).
- [ ] 3.4 Unit tests for the aggregation + cannibalization logic.

## 4. Local SEO foundation (schema only, no provider yet)

- [ ] 4.1 Migration `V148__seo_tracked_keyword.sql` — `seo_tracked_keyword` (design.md D1/D3):
      business_id, keyword, target_url nullable, location, device, active, created_at.
- [ ] 4.2 Owner-facing CRUD (add/edit/remove tracked keyword + its location), same
      `business_feature`-gated, owner-only pattern as the existing tracked-query endpoints. No rank
      data yet — this phase only lets the owner build their list before Phase 5 wires up real
      checks, so the list isn't empty on day one of Phase 5.
- [ ] 4.3 Keywords sub-view (frontend) shows the list + location badges, "rank data not connected
      yet" empty state — sets up Phase 5's UI slot without blocking on the provider decision.

## 5. Keyword rank tracking (needs Open Question 1 resolved — new cost, new credential)

- [ ] 5.1 Migration `V149__seo_rank_snapshot.sql` — `seo_rank_snapshot` (design.md D1): business_id,
      tracked_keyword_id, date, location, device, position, serp_features jsonb, checked_at.
- [ ] 5.2 New `DataForSeoClient` (or chosen provider, design.md D2) — thin `RestClient` wrapper,
      `SquareClient`-style semaphore+cache pair (design.md D12), own encrypted credential (new
      `SeoRankProviderCredential` row or reuse `seo_connection` with a nullable new column —
      decide based on whether this ends up per-business or app-wide at implementation time).
- [ ] 5.3 New `SeoRankTrackingSyncService` + `SeoRankTrackingSyncScheduler` (daily,
      `@SchedulerLock`, zone `America/Los_Angeles`) — mirrors `SeoSyncService`'s per-business-safe,
      try/caught shape. Add to `SchedulerLockAnnotationsTest`'s coverage.
- [ ] 5.4 Ranking history UI: trend chart (7d/30d/90d/1yr), 🟢/🔴/⚪ status, best/first/current
      position — Keywords sub-view.
- [ ] 5.5 Integration test for the new client (`MockRestServiceServer`, same pattern as
      `SearchConsoleClientTest`/`GoogleAnalyticsClientTest`), scheduler isolation test (mirrors
      `SeoSyncSchedulersTest`).

## 6. SEO AI Advisor (needs Open Question 1's spirit resolved — second AI feature, its own cost)

- [ ] 6.1 New `SeoContextBuilderService` (design.md D8) — the aggregation → filtering → ranking →
      structured-snapshot pipeline, named budget constants, pure/unit-testable.
- [ ] 6.2 Migration `V150__seo_analysis.sql` — `seo_analysis` (design.md D8): mirrors
      `funnel_analysis`'s shape (id, business_id, snapshot_fingerprint, language, prompt_version,
      model, data_snapshot jsonb, structured recommendation fields, created_at).
- [ ] 6.3 New `SeoAnalysisResult` record (+nested recommendation record with priority/action/why/
      evidence/expected-impact/effort/confidence/suggested-implementation/relevant-page-or-keyword
      fields, reusing the existing `ImpactLevel` enum), `SeoAdvisorPrompts` (system prompt +
      language directive, cached block per design.md D7).
- [ ] 6.4 New `SeoAiAdvisorService` (design.md D7) — package-private `callClaude`, fingerprint-cache
      `analyze(businessId, language, force)`, refusal handling, `SeoAdvisorFailedException`.
- [ ] 6.5 New `SeoAiAdvisorController` — `POST analyze` (owner-only, same gating shape as
      `FunnelAnalysisController`), `GET history` (`findTop20By...OrderByCreatedAtDesc`-style).
- [ ] 6.6 Advisor sub-view (frontend) — "Analyze SEO" CTA, last-analysis summary/status, top
      recommendation, wins/problems/opportunities/recommended-actions cards, expandable history
      list (folds in design.md D10's "History" screen).
- [ ] 6.7 Unit tests mirroring `FunnelAnalysisServiceTest`'s spy-and-override-`callClaude` pattern;
      fingerprint-cache regression test (same input twice → one Claude call); refusal-path test;
      language-directive test (RU vs EN).

## 7. Competitor intelligence (needs Open Question 1 resolved — same provider as Phase 5, plus GBP)

- [ ] 7.1 Migration `V151__seo_competitor.sql` — `seo_competitor` + `seo_competitor_metric`
      (design.md D9): source/confidence-labeled key-value shape.
- [ ] 7.2 Owner-facing competitor CRUD (~3 competitors, name/site/GBP info/location/notes/active,
      no hardcoded competitor anywhere).
- [ ] 7.3 Competitor comparison sync — whatever dimensions D2's provider + public GBP data can
      supply (keyword overlap, indexed-page count if available, PageSpeed/CWV comparison since
      that's already first-party data we can fetch for any URL, review count/rating if GBP data is
      reachable). Every value stored with its `source`/`confidence`.
- [ ] 7.4 Competitors sub-view (frontend) — comparison table/cards, explicit source/confidence
      labels on every cell, empty state when a competitor has no data for a given dimension yet
      (never a fabricated placeholder).
- [ ] 7.5 Feed available competitor summary into `SeoContextBuilderService` (Phase 6) once both
      phases are live, so the AI Advisor's "competitor gaps" section has real data instead of being
      permanently empty.

## 8. Alerts + final IA polish

- [ ] 8.1 Wire `SeoChangeDetectionService`'s output into an Overview alert-card list (design.md
      D11) — no new notification channel, dismissible per session.
- [ ] 8.2 Final pass on the 6-tab in-page navigation (design.md D10) across all phases' screens —
      consistent mobile tab strip, verify no cross-tab regressions.
- [ ] 8.3 Full manual E2E pass (throwaway env per `frontend/e2e/README.md`) across all 6 sub-views,
      both mobile (375px) and desktop viewports, plus a real login/pin/analyze/dismiss click-through
      — same rigor as this session's GA4-chart/tracked-query verification.
- [ ] 8.4 Regression pass: confirm the pre-existing `/owner/marketing/seo` overview, sync button,
      and tracked-query pin/unpin flow all still work unchanged for a business that never touches
      any of the new tabs (keyword tracking/competitors/AI advisor left at zero rows).
