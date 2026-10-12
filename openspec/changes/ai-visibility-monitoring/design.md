## Context

Owner decisions (2026-10-12): weekly cadence for everything; 5 engines (ChatGPT, Google AI Mode,
Gemini, Perplexity, Claude); Claude proposes the 20 questions per business and the owner approves
them in the UI; Telegram = one weekly digest per business, nothing else. Both businesses already
have `seo-monitoring.enabled` and a `telegram_notification_config` row (checked 2026-10-12).

The VPS prototype (`~/seo-monitoring/weekly_ai_pmu.py`, `dfs.py`) proved the DataForSEO calls,
costs and response shapes. This change moves that into the backend with storage and a UI.

## D1. Data source: DataForSEO, one account for the platform

| Engine | Endpoint | Model | Cost/question (measured 2026-10-12) |
|---|---|---|---|
| CHATGPT | `ai_optimization/chat_gpt/llm_scraper/live/advanced` (`location_code` 2840, `language_code` en) | real chatgpt.com answer | $0.004 |
| GOOGLE_AI_MODE | `serp/google/ai_mode/live/advanced` (`location_name` "San Diego,California,United States", mobile) | Google AI Mode | $0.004 |
| GEMINI | `ai_optimization/gemini/llm_responses/live`, `web_search: true` | gemini-3.5-flash | $0.03 |
| PERPLEXITY | `ai_optimization/perplexity/llm_responses/live`, `web_search: true` | sonar | $0.004 |
| CLAUDE | `ai_optimization/claude/llm_responses/live`, `web_search: true` | claude-haiku-4-5 | $0.024 |

- The ChatGPT scraper replaces the `gpt-4o-mini` API call the prototype used: it is 7x cheaper and
  is the answer a real user sees (model shown as gpt-6 on 2026-10-12), which is what consistency
  should measure.
- Model names live in config (`ai-visibility.engines.*.model`), so a model change is a config
  change, not a migration. The model actually used is stored on each check row.
- Credentials: `DATAFORSEO_LOGIN` / `DATAFORSEO_PASSWORD` env vars passed through
  `docker-compose.yml` like `ANTHROPIC_API_KEY` (one platform account, not a per-business secret,
  so no new cipher). Values are copied once from `~/seo-monitoring/credentials/dataforseo.json` into
  `~/salaryReview/.env`; never committed. When unset, the scheduler logs once and does nothing.
- Client: `DataForSeoClient` (RestClient, Basic auth, 120s timeout). Retries: HTTP
  403/429/5xx and task status 40101/50000 up to 2 times with 5s/10s backoff (both seen on
  2026-10-07/08). Each call returns `{status, cost, rawJson}`.

## D2. Schema (Flyway `V167__ai_visibility.sql`, internal-API PUT for `next_actions` included in D6)

Next free migration is V167 (highest on master is V166; openspec/config.yaml still says V112, it is
stale). All tables carry `business_id`.

```sql
CREATE TABLE ai_visibility_settings (
  business_id BIGINT PRIMARY KEY REFERENCES business(id),
  brand_aliases TEXT[] NOT NULL,        -- regex-safe literal names: 'AK.LUX.NAILS','AK Lux Nails','AKLuxNails'
  own_domains TEXT[] NOT NULL,          -- 'akluxnails.com' (subdomains match), PMU: 'pmu-annakara.com','edu.pmu-annakara.com'
  location_hint TEXT NOT NULL,          -- 'San Diego,California,United States'
  monthly_budget_usd NUMERIC(8,2) NOT NULL DEFAULT 8.00,
  digest_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  -- (V167 also adds ai_visibility_page, see D10)
  next_actions TEXT,                    -- 1-3 lines Claude sets weekly via the internal API (D8)
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE ai_visibility_query (
  id BIGSERIAL PRIMARY KEY,
  business_id BIGINT NOT NULL REFERENCES business(id),
  query_text TEXT NOT NULL,
  topic TEXT,                           -- 'brows','lips','russian manicure'... (grouping in UI + digest)
  target_url TEXT,                      -- our page meant to win this question (D10)
  status TEXT NOT NULL CHECK (status IN ('PROPOSED','ACTIVE','PAUSED','REMOVED')),
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  activated_at TIMESTAMPTZ,
  UNIQUE (business_id, lower(query_text)));   -- expression index, written as CREATE UNIQUE INDEX

CREATE TABLE ai_visibility_run (
  id BIGSERIAL PRIMARY KEY,
  business_id BIGINT NOT NULL REFERENCES business(id),
  trigger TEXT NOT NULL CHECK (trigger IN ('SCHEDULED','MANUAL_QUERY')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  checks_ok INT NOT NULL DEFAULT 0, checks_failed INT NOT NULL DEFAULT 0,
  cost_usd NUMERIC(8,4) NOT NULL DEFAULT 0,
  stopped_reason TEXT);                 -- 'BUDGET','NO_CREDENTIALS', null = completed

CREATE TABLE ai_visibility_check (
  id BIGSERIAL PRIMARY KEY,
  business_id BIGINT NOT NULL REFERENCES business(id),
  run_id BIGINT NOT NULL REFERENCES ai_visibility_run(id),
  query_id BIGINT NOT NULL REFERENCES ai_visibility_query(id),
  engine TEXT NOT NULL CHECK (engine IN ('CHATGPT','GOOGLE_AI_MODE','GEMINI','PERPLEXITY','CLAUDE')),
  model TEXT,
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status TEXT NOT NULL CHECK (status IN ('OK','NO_ANSWER','ERROR')),
  mentioned BOOLEAN,                    -- null unless status = OK
  mention_rank SMALLINT,                -- 1 = first place named; null if not named or unknown
  own_site_cited BOOLEAN,
  places_named JSONB NOT NULL DEFAULT '[]',   -- ["The Beau","Avo Brows & Beauty"] (max 10)
  sources_cited JSONB NOT NULL DEFAULT '[]',  -- [{"domain":"yelp.com","title":"..."}] (max 15, deduped by domain+title)
  sub_queries JSONB NOT NULL DEFAULT '[]',    -- fan_out_queries (max 10)
  answer_excerpt TEXT,                  -- first 1,500 chars; around our mention if named
  cost_usd NUMERIC(8,4),
  error TEXT);
CREATE INDEX ON ai_visibility_check (business_id, query_id, engine, checked_at DESC);
```

- Raw DataForSEO JSON is NOT stored (a ChatGPT scraper answer is 10-20 KB; 200 rows/week would grow
  fast for no use). The excerpt + parsed fields are enough for history; a parsing bug is fixed
  forward.
- `REMOVED` is a soft delete so history and consistency for old questions stay readable.
- Volume: 200 check rows per week for both businesses, about 10k rows/year. No retention job.

## D3. Run (scheduler)

- `AiVisibilityScheduler`: `@Scheduled(cron = "0 0 6 * * MON", zone = "America/Los_Angeles")` +
  `@SchedulerLock(name = "AiVisibilityScheduler_run", lockAtMostFor = "PT2H")`. For each business
  with `ai-visibility.enabled` and a settings row: `currentBusinessContext.runAs(businessId, ...)`,
  per-business try/catch (same shape as `SeoSearchConsoleSyncScheduler`).
- Order inside a run: question by question, engines in the order of the table above. Calls are
  sequential (DataForSEO live endpoints, about 5-40s each; a full business run takes about 15-25
  min, well inside the lock).
- Budget guard before every call: `spent this calendar month (PT) + expected cost of this call >
  monthly_budget_usd` → stop, `stopped_reason = 'BUDGET'`. Expected cost per engine is config.
- One failed call writes a `status = ERROR` row and the run continues. An engine that fails 3 times
  in a row is skipped for the rest of the run (provider outage), noted in the digest.
- Manual re-check (`MANUAL_QUERY`): one question × 5 engines, owner button, max once per question
  per PT day (409 otherwise), same budget guard. Runs async; the UI polls the run row.

## D4. Parsing (deterministic, per engine)

`AiAnswerParser` turns an engine response into `ParsedAnswer{text, citations[], subQueries[]}`:
- llm_responses (Gemini, Perplexity, Claude): text = all `items[].sections[].text`; citations =
  `sections[].annotations[]` (`title`, `url`). Gemini URLs are `vertexaisearch` redirects, so the
  domain comes from `title` when the URL host is that redirect. Sub-queries = `fan_out_queries`.
- ChatGPT scraper: text = `markdown`; citations from its sources/search results array and from
  markdown links.
- AI Mode: text and references from the `ai_overview`/AI Mode item; `NO_ANSWER` when Google shows
  no AI block.

`MentionDetector` (pure, unit-tested):
- `mentioned` = any brand alias matched as a whole phrase (case-insensitive, punctuation-tolerant:
  "AK.LUX.NAILS" ~ "AK Lux Nails" ~ "AKLuxNails") OR any own domain in text or citations.
  Guard against false positives such as "Anna's Nails" or "Kara Beauty": aliases are full names,
  never a single first name.
- `own_site_cited` = an own domain appears among citations.
- `places_named` = ordered list from bold/heading/numbered-list entities and citation titles that
  look like business names (filters for generic labels like "Specialties", "Location", "Price",
  "Highlights", payment brands; the prototype's filter list is the starting point), max 10.
- `mention_rank` = 1-based position of our first alias match among `places_named`; null if not
  found there.

## D5. Consistency and summaries (`AiVisibilityQueryService`)

- Per question × engine: `consistencyAll = named / OK checks` over all checks, `consistency8w` over
  the last 8 weeks, plus `n/N`. Cells with `N < 3` show the count only (no percentage yet), so a
  1-of-1 never reads as "100%".
- Latest state per cell = the most recent check (OK → green/red; NO_ANSWER or ERROR → grey with a
  tooltip; none → grey "not checked yet").
- Per engine this run vs previous run: `named/answered` counts.
- "Who AI recommends instead": over the last 4 weeks of OK checks where `mentioned = false`, the
  top 8 `places_named` and top 8 cited domains (excluding own domains), with counts and the
  questions they appeared on.
- One SQL aggregation per page load (window over `ai_visibility_check` for the business); no
  caching needed at 10k rows/year.

## D6. API and security

Owner endpoints (role OWNER, business-scoped via `CurrentBusinessContext`; every id-taking repo
method is `...AndBusinessId`):
- `GET /api/owner/marketing/ai/overview` → engines, last/next run, month spend + budget, rows
  (question, topic, status, per-engine cell {latest, checkedAt, consistency8w, consistencyAll, n, N}),
  "instead" lists.
- `GET /api/owner/marketing/ai/queries/{id}/history?limit=52` → checks for one question (all
  engines), newest first.
- `POST /api/owner/marketing/ai/queries` (add, max 20 ACTIVE + PROPOSED combined per business),
  `PATCH /api/owner/marketing/ai/queries/{id}` (text, topic, status, sort), `POST
  .../queries/{id}/recheck`, `POST .../queries/approve-all` (PROPOSED → ACTIVE).
- `GET /api/owner/marketing/ai/queries/{id}/pages` → page comparison for one question (D10);
  `POST .../queries/{id}/pages/refresh` (once per question per day);
  `GET /api/owner/marketing/ai/page-gaps` → rolled-up gaps; `POST
  /api/owner/marketing/ai/competitors` → adds a named place to the existing `seo_competitor` list
  (business-scoped, reuses `SeoCompetitorService.addCompetitor`).
- `GET/PUT /api/owner/settings/ai-visibility` (aliases, domains, budget, digest on/off).
Internal: `GET /api/internal/ai-visibility/{businessId}/summary` (`X-Internal-Api-Key`) returns
the overview JSON + last 8 weeks of checks, for Claude's analysis from the server, and
`PUT /api/internal/ai-visibility/{businessId}/next-actions` sets the digest's "this week" lines.
SecurityConfig: `/api/owner/marketing/ai/**` and `/api/owner/settings/ai-visibility` → OWNER.
`MeController` features map gains `aiVisibilityEnabled`.

## D7. UI

- **Tab strip** (`MarketingTabs.tsx`): replace the `overflow-x-auto` strip with a wrapping layout:
  below `sm`, a 4-column grid of compact tabs (7 tabs → 2 rows, active tab highlighted, min 44px
  touch height); from `sm` up, the current single row with `flex-wrap`. The landing-page selector
  and Sync button stay in their own row below. This applies to all marketing tabs.
- **AI tab** (`/owner/marketing/ai`, server component fetching overview, client islands for
  actions):
  - Header cards: per engine "named in X of Y this week (last week Z)"; spend "$2.61 of $8 this
    month"; "Last check Oct 19 · next Oct 26".
  - Table (≥ md): columns Question | ChatGPT | Google AI | Gemini | Perplexity | Claude | History.
    Cell = chip (green "Named #2" / red "Not named" / grey "No answer") + small line "86% · 6/7"
    and the check date in the row's small text.
  - Mobile (< md): one card per question: question text, 5 chips in a wrapping row with the
    engine short name and percentage, small "checked Oct 19", History link. No horizontal scroll.
  - Grouped by `topic` with a subtle group header; sort by `sort_order`.
  - History: a sheet (bottom sheet on mobile, side panel on desktop) with a week-by-engine grid of
    dots on top (up to 52 weeks), then each check: date, engine, named/rank, places named, cited
    sources (domains, linked), excerpt (collapsed). Lazy-loaded on open.
  - "Who AI recommends instead" panel under the table (two short ranked lists).
  - PROPOSED questions show in a yellow "Suggested by Claude" block at the top with Approve,
    Edit, Remove per item and "Approve all". Add-question field with a 20-limit counter.
  - Empty state before the first run: "First check runs Monday 6:00 AM PT" + the proposed list.

## D8. Weekly Telegram digest

Sent per business after its run finishes, through that business's `telegram_notification_config`
(same send path as `TelegramNotificationService`). Plain text, about 10 lines:

```
🤖 AI-рекомендации, AK.LUX.NAILS, неделя 19.10
ChatGPT 6/20 (+2) · Google AI 3/20 (=) · Gemini 15/20 (-1) · Perplexity 12/20 (+1) · Claude 9/20 (new)
Появились: «best russian pedicure san diego» в ChatGPT, ...
Пропали: «best nail salon downtown» в Gemini
Вместо нас чаще всего: Twinkle Nails (9), LV Nails (7), Nail Care & Spa (5)
Источники, где нас нет: yelp.com (14), sayheysandiego.com (6), expertise.com (4)
В работе у Claude: <1-3 lines from ai_visibility_settings.next_actions, if set>
Расход: $1.31 из $8 · подробнее: <owner URL>/owner/marketing/ai
```

- Russian copy (owner's language), no em-dashes.
- "В работе у Claude" comes from a short free-text field `next_actions` on
  `ai_visibility_settings` (added in V167), which Claude updates via the internal API after
  reviewing the summary; omitted when empty. This keeps the digest actionable without an LLM call
  in the backend. The digest goes out right after the run, so it shows the actions Claude set after
the previous digest (work in progress); Claude replaces them after reading the new numbers.
- Not sent when the run stopped before any OK check (an error line goes to the backend log and the
  next digest mentions it).

## D9. Seed data

V167 seeds `ai_visibility_settings` for businesses 1 and 2 (aliases, domains, budget $8) and 20
`PROPOSED` questions each (see tasks.md 1.3), so the owner only approves. `ai-visibility.enabled`
is seeded `false`; it is turned on per business after deploy.

## D10. Page comparison (component gaps)

Goal (owner, 2026-10-12, from the InWeb session): besides "which source cites the competitor",
compare what the competitor's page contains that ours does not (a before/after block, an FAQ, ...)
and add the missing parts to our pages.

**Target page per question.** `ai_visibility_query.target_url` (nullable, our page meant to win the
question). Seeded in V167 (for example russian manicure → `https://akluxnails.com/`, pedicure →
`/russian-pedicure`, extensions → `/gel-nail-extensions`, PMU brows → `/microblading-san-diego/`,
lips → `/permanent-makeup-lips/`, scar → `/scar-camouflage/`). The UI suggests the page with the
most Search Console clicks for similar queries when it is empty.

**Which competitor pages.** From the last 4 weeks of OK checks for that question: cited URLs whose
domain is not ours and not a directory/list site (directories such as yelp.com, expertise.com,
sayheysandiego.com are tracked as *source gaps*, D5), plus the website of a place named in the
answer when it matches a `seo_competitor.website`. Up to 5 competitor pages per question, most
frequently cited first. Gemini redirect URLs are resolved with a HEAD request.

**Fetching** (`PageFetcher`): monthly, the first Monday of the month right after the AI run
(ShedLock), and on demand from the UI (once per question per day). Plain GET with a normal
browser User-Agent, 15s timeout, robots.txt respected, max 1 request per domain per 2 seconds,
cached per URL for 28 days by content hash. If the fetch fails or returns an empty JS shell (less
than 300 visible words), fall back to DataForSEO On-Page `instant_pages` with a `custom_js` that
returns a compact JSON of exactly what the extractor needs (JSON-LD blocks, headings, image
src/alt, iframe/video sources, link texts and hrefs, visible text). Measured 2026-10-12 on
thebeaupmu.com, which answered our plain request with 429: $0.00015 per page without JS
rendering, $0.0015 with it (used only when the first try returns an empty shell). The extractor
works on either input (jsoup DOM or that JSON). Hard cap 40 fetched pages per business per month, counted toward the same budget guard.

**Extraction** (`PageComponentExtractor`, jsoup, deterministic, unit-tested on saved HTML). Each
component is `present / absent` plus a short evidence string (the heading or selector that matched):

| Component | Detected when |
|---|---|
| BEFORE_AFTER | ≥ 2 images whose alt/filename/caption or nearest heading contains "before"/"after", or a comparison slider |
| FAQ | `FAQPage` schema, or a heading with "FAQ"/"Questions" followed by ≥ 3 question-style items |
| PRICES | ≥ 2 dollar amounts near service names, or `Offer`/`PriceSpecification` schema |
| REVIEWS_ON_PAGE | `Review`/`AggregateRating` schema or a testimonials block with ≥ 2 quotes |
| RATING_STATED | text like "4.9 stars", "5.0 (120 reviews)" |
| ARTIST_BIO | `Person` schema or a section about the artist with years of experience/certifications |
| PROCESS_STEPS | heading "process"/"how it works"/"what to expect" + ordered list or ≥ 3 step headings |
| AFTERCARE | heading or section with "aftercare"/"healing" |
| VIDEO | `<video>` or YouTube/Vimeo/Instagram embed |
| BOOKING_CTA | link or button to a booking flow (book/schedule/consultation) in the first screen of HTML |
| FINANCING | "financing", "payment plan", "Cherry", "Afterpay", "Klarna" |
| MAP_NAP | map embed or full street address + phone on the page |
| SCHEMA_TYPES | list of JSON-LD `@type`s (LocalBusiness/HealthAndBeautyBusiness, FAQPage, Review, Service, BreadcrumbList) |
| PRESS | "as featured in"/"as seen on" block or press logos |
| GUARANTEE | "guarantee", "touch-up included", "free correction" |
| UPDATED_DATE | visible "updated" date or `dateModified` |
| Metrics | word count, H2 count, image count, list of H2 texts (for the History view only) |

Storage: `ai_visibility_page` (business_id, url, is_own, last_fetched_at, http_status,
fetch_method DIRECT/ONPAGE, content_hash, components JSONB, metrics JSONB, error). One row per URL,
overwritten on refetch, plus `components_history` JSONB keeping the last 6 snapshots (enough to
see "competitor added an FAQ in March"). Added to V167.

**Gap rule** (computed on read, no table): for a question, a component is a gap when our target
page lacks it and at least 2 of the fetched competitor pages for that question have it (or 1 of 1
when only one competitor page exists, shown as "weak"). Gaps across questions are rolled up per our
target page: "`/russian-pedicure`: FAQ missing (4 of 5 competitor pages have it, affects 3
questions)".

**UI**: in the History sheet, a "Page comparison" tab: rows = components, columns = our page + up to
5 competitor pages, ✓/✗ cells with evidence on tap, competitor URL headers linked. On the AI tab, a
"Page gaps" panel ranked by (questions affected × competitor share), each with the target page and
the "how to fill it honestly" hint (for example BEFORE_AFTER: "real photos with client consent";
FAQ: "answers from confirmed facts").

**Digest**: at most 2 lines, for example "Чего не хватает на наших страницах: FAQ на
/russian-pedicure (есть у 4 из 5 конкурентов, 3 запроса)". Only on the first Monday of the month,
after the refetch, so the line is not repeated weekly.

**Guardrails**: only component presence is compared; competitor text is never stored beyond H2
texts and the 1-line evidence. Filling a gap is a task for Claude through a normal PR; on
pmu-annakara.com it follows GROWTH_PLAN.md section 0 (additive blocks, owner sees the text first,
max 3-5 pages per week, real photos and facts only).

## Risks

- **Answer variance**: LLM answers vary between runs. Weekly single samples make consistency
  meaningful only after several weeks; the UI hides percentages below 3 checks and always shows
  n/N.
- **Location**: LLM endpoints get "San Diego" from the question text, not from a geo setting (only
  ChatGPT scraper and AI Mode take a location). Every question includes "San Diego" or a
  neighborhood.
- **Parser drift**: DataForSEO response shape changes would show as many `places_named = []`.
  The digest flags a run where more than half of OK checks have no places named.
- **Blocked fetches**: some competitor sites block bots (Cloudflare). The On-Page fallback covers
  most; a page that still fails is shown as "not readable", never counted as missing components.
- **Detection errors**: heuristics can miss a component (an FAQ built as an accordion with no
  headings). Evidence strings make each ✓ checkable, and the gap rule needs 2 competitor pages, so
  one misread page does not create a gap by itself. Extractor fixtures grow with each false
  reading found.
- **Cost drift**: per-engine cost is stored per check; the budget guard caps any surprise at $8 per
  business per month.
