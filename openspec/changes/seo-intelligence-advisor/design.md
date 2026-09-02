## Context

**Current foundation** (highest migration `V147`, all already merged/deployed):

- `SeoConnection` (`V139`) — one row per business, AES-256-GCM-encrypted GSC service-account JSON
  + GA4 property/measurement ID + PageSpeed API key, own master key (`SEO_CREDENTIALS_MASTER_KEY`,
  `SeoCredentialCipher`, independent of `SquareCredentialCipher`). `SeoConnectionService` decrypts
  on demand; never logged, never sent to the frontend.
- `SearchConsoleClient`/`GoogleAnalyticsClient`/`PageSpeedInsightsClient` — thin `RestClient`
  wrappers (`GoogleRestClients` factory forces `MappingJackson2HttpMessageConverter` since this
  app's default Jackson converters are a newer major version incompatible with
  `com.fasterxml.jackson.databind.JsonNode`), authenticated via `GoogleServiceAccountAuth`
  (hand-rolled RS256 JWT-bearer, one instance per business, no Google SDK). Confirmed today:
  `SearchConsoleClient.queryPerformance` requests `dimensions=["query","page"]` only, one HTTP call
  per day in a loop — no country/device/searchAppearance dimension is pulled today.
  `GoogleAnalyticsClient.dailyTotals` was just fixed (this session) to fetch its whole 28-day
  window via a `"date"` dimension breakdown in 2 calls total, after the original per-day-loop
  version (56 calls) tripped the reverse proxy's 60s read timeout on "Sync now" — the same
  per-day-loop pattern still exists unfixed in `SearchConsoleClient`, a latent, not-yet-urgent risk
  (D12).
- `SeoSyncService` — shared sync logic (`syncSearchConsole`/`syncAnalytics`/`syncPageSpeed`), each
  independently try/caught so one Google API failing never blocks another, called by both
  `SeoSearchConsoleSyncScheduler` (daily, `0 0 3 * * *`) /`SeoPageSpeedSyncScheduler` (weekly,
  `0 0 5 * * MON`, deliberately coarse because of a real PageSpeed-quota incident during manual
  testing) and the manual `POST /api/owner/marketing/seo/sync` button.
- `seo_search_metrics_snapshot` (query/page/date grain, 28-day rolling window, re-upserted every
  sync), `seo_page_snapshot` (CWV per strategy, weekly), `seo_analytics_snapshot` (GA4 totals,
  daily, added this session), `seo_tracked_query` (owner pin list, added this session, hybrid
  pin/auto-suggest-by-impressions fallback already implemented in
  `SeoDashboardService.trackedQueries`), `seo_technical_issue` (auto-opened/resolved by
  `SeoIssueFlaggingService`, currently LCP+CLS thresholds only, `CoreWebVitalsThresholds` sourced
  from web.dev, CTR-opportunity heuristic — impressions ≥50 and CTR <50% of trailing site average).
- `SeoDashboardService`/`SeoDashboardController` — read model + `GET overview`/`POST sync`/
  `POST,DELETE tracked-queries`, gated by `BusinessFeatureService.isEnabled(businessId,
  "seo-monitoring.enabled")` (two-layer gate: deployment env flag decides "possible at all,"
  `business_feature` row decides "on for this business").
- `SeoDashboardView.tsx` — recharts (`ResponsiveContainer`+`LineChart`), emerald/rose delta
  convention (borrowed from `RevenueChart.tsx`), `hidden sm:table-cell` mobile-hide convention,
  `overflow-x-auto` wide-table wrapper — all reusable as-is for new screens. The shared `<main>`
  mobile-overflow bug (flex item with `mx-auto` but no `w-full`) was already found and fixed
  across all 6 marketing tab pages this session; new screens must not reintroduce it.
- **AI**: `com.salonreview.ai.FunnelAnalysisService`/`FunnelAnalysisController`/`FunnelAnalysis`
  entity is the exact template for Feature 10 (full breakdown in D7). A single shared
  `AnthropicClient` bean (`AnthropicClientConfig`, official Anthropic Java SDK,
  `ObjectProvider<AnthropicClient>` injection so consumers boot cleanly with AI off) is registered
  whenever any AI feature flag is on; the credential (`ANTHROPIC_API_KEY`, plain env var, not
  per-business-encrypted) is shared app-wide.
- **Scheduling**: `@EnableSchedulerLock` (`shedlock` table, `V64`) + `@SchedulerLock` on every
  `@Scheduled` method, zone always `America/Los_Angeles` explicitly (server runs UTC) — enforced by
  a reflection-based CI guard, `SchedulerLockAnnotationsTest`, that fails if any `@Scheduled` method
  lacks a matching lock annotation.
- **i18n for AI features specifically**: not a business setting — `AppUser.preferredLanguage`
  (`Language` enum, `EN`/`RU`, nullable, resolved fresh from the DB per request via a small
  `language(AppUserPrincipal me)` helper duplicated in `RagController` and
  `FunnelAnalysisController`).

## Decisions

### D1 — Keyword rank tracking is a new capability, not an extension of `seo_search_metrics_snapshot`

**Decision:** New table `seo_rank_snapshot` (business_id, tracked_keyword_id, date, location,
device, position, serp_features jsonb, checked_at), new `seo_tracked_keyword` table (business_id,
keyword, target_url nullable, location, device, active, created_at) — a genuinely separate concept
from `seo_tracked_query` (today's Search-Console-impressions-derived pin list) and from
`seo_search_metrics_snapshot` (Google's own blended average position).

**Why:** The proposal's own explicit instruction is to never conflate "Google Search Console
average position" with "actual tracked SERP position." Reusing `seo_search_metrics_snapshot`'s
shape would either silently blend the two concepts or require a nullable "source" discriminator
column that makes every downstream query need a `WHERE source = ...` guard. A dedicated table
keeps the UI's own distinction (design.md non-negotiable) enforced at the schema level, and keeps
`seo_tracked_query`'s existing hybrid pin/auto-suggest logic (already shipped, already tested)
completely untouched.

### D2 — External rank-tracking/competitor provider: DataForSEO, not Semrush/Ahrefs/SerpApi-only

**Decision:** Recommend **DataForSEO** (SERP API + Rank Tracker endpoints) as the new external
dependency for real SERP position checks and competitor SERP comparison. Do not recommend
Semrush/Ahrefs; note SerpApi as a viable close second.

**Why:** AK.LUX.NAILS needs geo-targeted rank checks for 10-50 keywords (location = Downtown San
Diego/92101, not "San Diego metro"), checked at most daily — a low, bursty query volume for a
single-location business. DataForSEO and SerpApi are both pay-per-lookup (fractions of a cent to a
few cents per keyword-location-device check at this volume — realistically a few dollars a month),
both support explicit location targeting precise enough for a ZIP/neighborhood, and both are
already how the "get a real Google SERP position" problem is solved industry-wide when you don't
want to build/maintain a scraper. Semrush/Ahrefs are subscription products ($99-249+/mo minimum)
built around competitor content/backlink research at a scale this single-location salon doesn't
need yet — a fixed monthly cost that would dwarf the actual per-lookup cost of the volume this
business generates. DataForSEO edges out SerpApi on this specific use case because it also offers
a Rank Tracker endpoint purpose-built for "check these N keywords at this location on a schedule"
(vs. SerpApi's more general raw-SERP-fetch shape, which would need more app-side logic to turn into
a rank tracker), and its separate Domain Analytics/backlink module is a plausible same-vendor
fast-follow if competitor backlink data is ever justified, without adding a second provider
relationship later.

**Complexity:** Low-Medium — one new thin `RestClient` wrapper class following
`SearchConsoleClient`'s own "map only what we use" convention, one new encrypted-credential row
(reuse `SeoCredentialCipher`'s pattern, new master key env var `SEO_RANK_PROVIDER_API_KEY` — a
single app-wide key is fine here, unlike per-business GSC credentials, since DataForSEO is billed
to this app's own account, not the business's own Google property), one new scheduled job, one new
`Semaphore`+cache pair mirroring `SquareClient.throttled`/`cached` (no generic rate-limit wrapper
exists yet in this codebase — see D12).

### D3 — Local SEO location is a first-class column, not a UI label

**Decision:** `seo_tracked_keyword.location` is a required, free-text-but-validated field (one of
a small owner-editable list seeded with "Downtown San Diego" / "92101"), passed through to the
rank-tracking provider's own location-targeting parameter on every check, and rendered as a visible
badge next to every tracked keyword and on every rank chart — never assumed or hidden.

**Why:** The proposal explicitly warns against assuming a ranking in one location represents the
whole San Diego market. Making location a stored, queried, and always-rendered column (not a
default baked into the provider call and forgotten) is the only way to keep that distinction real
rather than aspirational once there are 50 keywords across multiple owner-added locations.

### D4 — Gainers/losers and opportunity thresholds live in a new, testable `SeoChangeDetectionService`

**Decision:** New service, same shape as `SeoIssueFlaggingService` (pure logic, no controller
dependency, unit-testable with mocked repositories) with named constants (mirroring
`CoreWebVitalsThresholds`) for: significant-position-change floor (e.g. ≥4-position move, avoids
"#10→#9" noise per the proposal's own example), significant-impression/click percentage change
floor, striking-distance band (#4-20 + impressions ≥ some floor), high-impression/low-CTR
opportunity (reuses the existing CTR-opportunity heuristic's shape/thresholds rather than
inventing a second one).

**Why:** Every existing threshold-based signal in this codebase (`CoreWebVitalsThresholds`,
`SeoIssueFlaggingService`'s CTR heuristic) lives as named constants in one small, independently
testable class — this is the established, working convention, and the proposal explicitly asks
for configurable, sensible, non-noisy thresholds, which named constants with doc-commented
rationale satisfy without a new dynamic-config system that nothing else in this feature area needs
yet.

### D5 — Query→page mapping / cannibalization detection needs no new data, only new aggregation

**Decision:** New read-only method on `SeoDashboardService` (or a small dedicated
`SeoCannibalizationService` if the logic grows past a few lines): group existing
`seo_search_metrics_snapshot` rows by `query`, and where more than one distinct `page` has
meaningful impressions/clicks for the same query in the current window, surface it as a flagged
row — labeled "potential optimization opportunity," per the proposal's own explicit instruction
not to assert it's wrong.

**Why:** `seo_search_metrics_snapshot` already carries both `query` and `page` per row — this is
purely a presentation/aggregation gap, not a data gap. No new table, no new sync, no new external
dependency.

### D6 — Technical SEO stays within what Search Console/PageSpeed can actually answer

**Decision:** Add FCP/TBT thresholds to `SeoIssueFlaggingService` (data already captured in
`seo_page_snapshot`, just not evaluated). Do **not** add sitemap/robots/indexation/broken-link/
duplicate-content/schema checks in this change — flag as a separate, explicitly-out-of-scope future
requirement needing either GSC's URL Inspection API (available but unused today — worth a
dedicated fast-follow proposal, not bundled into this one, since it's a per-URL, not per-site,
quota-bound API) or a real crawler.

**Why:** The proposal explicitly forbids inventing data the existing Google APIs can't provide.
FCP/TBT is a one-line addition to code that already computes the analogous LCP/CLS logic; the rest
of Feature 8's wishlist requires new infrastructure this change doesn't need to gate on.

### D7 — SEO AI Advisor is built as `FunnelAnalysisService`'s architecture, not a new pattern

**Decision:** New `SeoAiAdvisorService`/`SeoAiAdvisorController`/`SeoAnalysis` entity, copying
`FunnelAnalysisService`'s shape line-for-line:
- Shared `AnthropicClient` bean via `ObjectProvider` (already conditional on any AI flag; add
  `ai.seo-advisor.enabled` to the existing `@ConditionalOnExpression`).
- `claude-sonnet-5` hardcoded (matches this codebase's established "low-frequency, owner-triggered
  consultant task" bar — same justification `FunnelAnalysisService`/`SmsDraftService` already use).
- Structured output via `StructuredMessageCreateParams<SeoAnalysisResult>` +
  `@JsonPropertyDescription`-annotated record fields — no manual JSON parsing/validation, reusing
  the existing `ImpactLevel` (`HIGH`/`MEDIUM`/`LOW`) enum as-is for each recommendation's expected
  impact.
- System prompt as a cached `TextBlockParam` (`CacheControlEphemeral`), with the language directive
  in a **second, uncached** block appended after it — same reasoning as `FunnelAnalysisPrompts`:
  keeps the common-case (English) cached prefix stable, only non-English requests pay extra
  uncached tokens.
- Cost control via a deterministic `snapshotFingerprint` (built from exactly the numbers in the
  structured snapshot fed to the LLM, see D8) — `analyze(businessId, language, force)` returns the
  cached `SeoAnalysis` row unless `force=true` (an explicit "Analyze again" button), exactly
  mirroring `FunnelAnalysisService.analyze`. No scheduled/automatic AI call is added anywhere.
- Refusal handling (`stopReason` containing `"refusal"` → graceful bilingual fallback, never a hard
  error) and a `SeoAdvisorFailedException` → 502 contract, mirroring `AnalysisFailedException`.
- Package-private `callClaude(...)`, tested via `Mockito.spy()` + `doReturn`/`doThrow` override —
  the exact test shape already used in `FunnelAnalysisServiceTest`, not SDK-internals mocking.
- Language resolution: extract `FunnelAnalysisController`'s duplicated `language(AppUserPrincipal
  me)` helper into a small shared utility this time (it would otherwise be duplicated a 3rd time
  across `RagController`/`FunnelAnalysisController`/`SeoAiAdvisorController`) — a small, safe
  refactor of already-identical code, not a redesign.

**Why:** This is close to a solved problem in this exact codebase already. Every one of the
proposal's Feature 10 requirements (owner-triggered, language-aware, cost-controlled,
structured/actionable output, persisted history, no silent auto-calling) has a working, tested
precedent one file away. Inventing a different pattern would cost real engineering time for zero
benefit and would leave two different AI-feature shapes in the same codebase for a future engineer
to reconcile.

### D8 — Context budget pipeline is new work; snapshot is stored as one JSON blob plus a few normalized index columns

**Decision:** New `SeoContextBuilderService` implements the requested pipeline (raw data →
aggregation → significance filtering → ranking → prioritization → structured snapshot) as a plain,
independently unit-testable Java class with named, doc-commented budget constants (top 20
gainers/losers, top 30 opportunities, top 20 pages, top 30 queries, technical issues, competitor
summary, prior 3 analyses' recommendations) mirroring `CoreWebVitalsThresholds`'s "named constants,
not magic numbers" convention. The resulting structured snapshot (a Java record tree, not a raw
`Map`) is what both the LLM call and the persisted `seo_analysis.data_snapshot` column receive —
same object, serialized once. `seo_analysis` stores `data_snapshot jsonb` (the full structured
snapshot, for exact historical reproducibility — "what did the AI actually see") **plus** a small
set of normalized columns (`snapshot_fingerprint`, `language`, `model`, `prompt_version`,
`overall_status`) that the history list/filtering queries actually need, so the history view never
has to deserialize the JSON blob just to render a list of past analyses — same pattern
`FunnelAnalysis` already uses (structured `@JdbcTypeCode(SqlTypes.JSON)` fields alongside plain
indexed columns).

**Why:** No existing context-budgeting utility exists in this codebase (confirmed — every current
AI feature operates on small, already-bounded inputs; this is genuinely new). The proposal is
explicit that data should never be silently truncated and that the AI should never lose critical
information to a token limit — a ranked/prioritized structured snapshot with named budget
constants (not a blind `.take(20)` with no significance ranking behind it) is what satisfies that.
Storing the full snapshot as JSON (not fully normalizing every nested list into its own table)
matches this codebase's own precedent for exactly this kind of "structured, nested LLM
input/output" data (`FunnelAnalysis`'s own `recommendations_json`) rather than inventing a heavier
normalized schema nothing else in this app uses for LLM-adjacent data.

### D9 — Competitor data is explicitly source-labeled and confidence-scored, never blended with Google's own data

**Decision:** New `seo_competitor` table (business_id, name, website, gbp_place_id nullable,
location, notes, active, created_at) plus `seo_competitor_metric` (competitor_id, metric_type,
value, source enum, confidence enum, checked_at) — a generic key-value-with-provenance shape
rather than one wide table with a fixed column per metric, since which metrics are even available
depends entirely on what D2's provider and public GBP data can supply, and that surface is
expected to grow.

**Why:** The proposal's hard requirement — never present estimated/third-party data as exact
Google data — is easiest to enforce structurally when every stored value carries its own
`source`/`confidence` fields rather than living in an untyped column that a future screen might
render without checking provenance.

### D10 — IA: 6 sub-views, not 8, behind an in-page tab strip

**Decision:** Overview / Keywords / Pages / Technical / Competitors / Advisor. "Opportunities"
(the proposal's suggested 7th tab) folds into Keywords (keyword-level opportunities: striking
distance, gaining impressions) and Pages (page-level opportunities: high-impression/low-CTR,
cannibalization) rather than a separate screen, since every opportunity type is already a natural
sub-section of one of those two. "History" (the suggested 8th tab) folds into Advisor as an
expandable list below the current/latest analysis, mirroring how `FunnelAnalysis`'s own history is
presented today. New sub-navigation is an in-page horizontal tab strip (same
`overflow-x-auto`/`hidden sm:table-cell`-style mobile convention already used by `MarketingTabs.tsx`
one level up), not a second-level page route per sub-view, keeping the existing single
`/owner/marketing/seo` route and its existing `business_feature`/auth gating exactly as-is.

**Why:** The proposal explicitly asks for the *best* IA for this codebase, not to blindly follow
its own suggested skeleton, and separately asks to keep tab/screen count manageable. Two of the
suggested 8 tabs are natural sub-sections of others once the actual data model is considered.

### D11 — Alerts render inside the existing Overview, no new notification channel

**Decision:** Alerts (rank drop, traffic drop/spike, new technical issue, striking-distance entry)
are computed by `SeoChangeDetectionService` (D4) and rendered as a dismissible-per-session card
list at the top of Overview — no email/SMS/push channel, no new `alert` table beyond what
`seo_technical_issue` (existing) and the new gainers/losers computation already produce on demand.

**Why:** This app has no existing owner-facing push/email notification infrastructure for
marketing-tab features to hook into (Square/Twilio integrations are customer-facing, not
owner-alerting), and the proposal's own examples ("dropped from #3 to #8," "clicks decreased 24%")
are exactly what Overview already needs to show as gainers/losers — building a separate alerting
delivery mechanism for the same underlying computation would be a second UI for the same data with
no stated need for out-of-band delivery yet.

### D12 — Rate limiting for the new provider mirrors `SquareClient`, not a new abstraction

**Decision:** The new DataForSEO client wraps outbound calls in a `Semaphore`-gated `throttled(...)`
helper plus a short-TTL in-memory `cached(...)` helper, matching `SquareClient`'s existing
`MAX_CONCURRENT_SQUARE_CALLS`/cache pattern exactly (concurrency cap tuned to DataForSEO's own
documented rate limit at implementation time), documented in `docs/CACHING.md` alongside the
existing entries rather than a new doc. As a related, tracked-but-not-bundled cleanup:
`SearchConsoleClient.queryPerformance`'s per-day-loop (same shape as the GA4 bug just fixed this
session) should get the same date-dimension-range treatment in a small follow-up PR before this
change adds keyword-tracking sync load to the same "Sync now" request path — noted here so it
isn't lost, not executed as part of this proposal.

**Why:** No generic rate-limiter/retry abstraction exists in this codebase yet; `SquareClient`'s
semaphore+cache pair is the only real precedent for "outbound call to a rate-limited third-party
API," and `docs/CACHING.md` is this repo's established single source of truth for that kind of
rationale.

## Risks

- **External provider dependency**: DataForSEO/SerpApi-style rank-tracking APIs sit in the same
  ToS gray area every SERP-tracking tool in the industry operates in (none of Google's official
  APIs return a real, geo-targeted organic rank). Standard practice, not a novel risk, but worth the
  owner's explicit awareness before enabling.
- **Cost creep**: pay-per-lookup is cheap at 10-50 keywords checked daily, but scales linearly if
  the owner later wants many more keywords/locations or a higher check frequency — needs a
  visible cost/usage indicator, not just a silent bill.
- **Second AI feature doubles the app's exposure to Claude API cost/availability** — mitigated by
  copying `FunnelAnalysisService`'s existing fingerprint-cache discipline exactly; a regression
  here (e.g. a fingerprint that changes on every call, defeating the cache) would be a real,
  previously-seen-shape bug class to specifically test against.
- **`SearchConsoleClient`'s existing per-day-loop** (D12) becomes a second latent 504 risk once
  keyword-tracking sync is added to the same manual "Sync now" request — should be fixed before or
  alongside Phase 1, not left for a future incident to surface it the way the GA4 one did.
- **Local SEO data ceiling**: even with real rank tracking, this app cannot provide true Google Maps
  local-pack position (that needs GBP's own Business Profile Performance API, a separate, currently
  unconnected integration) — D2's provider gives organic SERP rank, not Maps-pack rank; this
  distinction must stay visible in the UI, not just in this doc.

## Open Questions

1. **Blocking for Phase 1**: does the owner want to enable a paid external provider (D2) at all, or
   ship Phases 1-4 (audit foundation, executive dashboard reshuffle, gainers/losers, page/query
   analysis) first — all achievable with zero new cost — and defer keyword-tracking/competitor
   phases until that's proven valuable? Recommend the latter as the safer default rollout order
   (tasks.md already sequences it this way).
2. **Not blocking**: exact DataForSEO vs SerpApi final pick — D2 recommends DataForSEO but either
   is viable; final choice can wait until Phase 5 actually starts.
3. **Not blocking**: whether `SearchConsoleClient`'s per-day-loop fix (D12) ships as its own small
   PR before Phase 1, or is folded into Phase 1's own task list — either works, just shouldn't be
   forgotten.
