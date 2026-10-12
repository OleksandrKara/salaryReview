## ADDED Requirements

### Requirement: Owner-curated AI questions per business
The system SHALL keep, per business, a list of AI questions with status `PROPOSED`, `ACTIVE`,
`PAUSED` or `REMOVED`, and SHALL allow at most 20 questions that are `ACTIVE` or `PROPOSED` at once.
Only `ACTIVE` questions are checked.

#### Scenario: Owner approves suggested questions
- **WHEN** the owner presses "Approve all" in the AI tab while 20 questions are `PROPOSED`
- **THEN** all 20 become `ACTIVE` with `activated_at` set, and the next scheduled run checks them

#### Scenario: Limit reached
- **WHEN** the owner adds a question while 20 questions are already `ACTIVE` or `PROPOSED`
- **THEN** the request is rejected with a message to pause or remove one first, and nothing is saved

#### Scenario: Removed question keeps its history
- **WHEN** the owner removes a question that has check history
- **THEN** its status becomes `REMOVED`, it is no longer checked or shown in the main table, and its
  checks remain in the database

#### Scenario: Cross-tenant id is rejected
- **WHEN** an owner of business 1 sends a PATCH or recheck for a question id that belongs to
  business 2
- **THEN** the response is 404 and nothing changes

### Requirement: Weekly check of every active question on five engines
Every Monday at 06:00 America/Los_Angeles the system SHALL, for each business with
`ai-visibility.enabled`, ask every `ACTIVE` question once to ChatGPT, Google AI Mode, Gemini,
Perplexity and Claude through DataForSEO, and SHALL store one `ai_visibility_check` row per
question and engine. Only one backend replica SHALL run it (ShedLock).

#### Scenario: Successful run
- **WHEN** the weekly run completes for a business with 20 active questions and no errors
- **THEN** 100 check rows with `status = OK` exist for that run, and the run row has
  `finished_at`, `checks_ok = 100`, and its total `cost_usd`

#### Scenario: One engine fails
- **WHEN** a DataForSEO call fails after its retries
- **THEN** a check row with `status = ERROR` and the error text is written and the run continues
  with the next call; after 3 consecutive failures of the same engine, that engine is skipped for
  the rest of the run

#### Scenario: No credentials
- **WHEN** `DATAFORSEO_LOGIN` or `DATAFORSEO_PASSWORD` is not set
- **THEN** no calls are made and the run row has `stopped_reason = 'NO_CREDENTIALS'`

### Requirement: Monthly budget guard
Before each DataForSEO call the system SHALL compare the business's spend in the current PT
calendar month plus the expected cost of that call with `monthly_budget_usd`, and SHALL NOT make
the call if it would exceed the budget.

#### Scenario: Budget reached mid-run
- **WHEN** the next call would raise this month's spend above the business's budget
- **THEN** the run stops with `stopped_reason = 'BUDGET'`, and the weekly digest says the budget
  was reached and how many checks were skipped

### Requirement: Deterministic mention, rank, competitor and citation parsing
For each OK answer the system SHALL set `mentioned` when a configured brand alias (whole phrase,
case- and punctuation-insensitive) or an own domain appears in the answer text or its citations,
SHALL record `own_site_cited`, up to 10 `places_named` in answer order, up to 15 `sources_cited`
domains, the assistant's sub-queries, `mention_rank`, and an excerpt of at most 1,500 characters.
No LLM is called to analyze answers.

#### Scenario: Brand named with different punctuation
- **WHEN** an answer contains "Ak Lux Nails" and the alias is "AK.LUX.NAILS"
- **THEN** `mentioned = true`

#### Scenario: Similar name is not a mention
- **WHEN** an answer names "Anna's Nails" and "Kara Beauty Bar" but none of PMU's aliases or domains
- **THEN** `mentioned = false` for PMU

#### Scenario: Cited but not named
- **WHEN** an answer cites a pmu-annakara.com page as a source but never names the studio in text
- **THEN** `mentioned = true` and `own_site_cited = true` and `mention_rank` is null

### Requirement: Consistency per question and engine
The system SHALL compute, per question and engine, the share of OK checks that named us, over all
checks and over the last 8 weeks, together with the counts; percentages SHALL be shown only when
there are at least 3 OK checks.

#### Scenario: Early weeks
- **WHEN** a question has 2 OK checks on ChatGPT, 1 of them named us
- **THEN** the cell shows "1/2" and no percentage

#### Scenario: Established history
- **WHEN** a question has 12 OK Gemini checks over 12 weeks, 11 of them named us, and all 8 checks
  of the last 8 weeks named us
- **THEN** the cell shows all-time "92% · 11/12" and 8-week "100% · 8/8"

### Requirement: AI tab in Marketing
When `ai-visibility.enabled` is on for the business, the Marketing section SHALL show an "AI" tab
next to "SEO" that lists every non-removed question with, per engine, a green (named), red (not
named) or grey (no answer, error, not checked) chip for the latest check, its consistency, and the
latest check date, plus a History view per question, per-engine weekly totals, month spend vs
budget, and the "who AI recommends instead" lists.

#### Scenario: Mobile layout
- **WHEN** the owner opens `/owner/marketing/ai` on a 390px-wide screen
- **THEN** each question is a card with all 5 engine chips visible, and the page does not scroll
  horizontally

#### Scenario: History
- **WHEN** the owner taps History on a question
- **THEN** a sheet lists every check for that question (newest first) with date, engine, named and
  rank, places named, cited sources and an excerpt

#### Scenario: Feature off
- **WHEN** `ai-visibility.enabled` is off for the business
- **THEN** the AI tab is not shown and its endpoints return 404

### Requirement: Every Marketing tab is visible on a phone
The Marketing tab navigation SHALL show all tabs without horizontal scrolling at widths of 360px
and above.

#### Scenario: Seven tabs on a phone
- **WHEN** the owner opens any Marketing page on a 390px-wide screen with SEO and AI enabled
- **THEN** Overview, Contacts, Funnel, Ads Report, LTV, SEO and AI are all visible at once, wrapped
  into rows, and the active tab is highlighted

### Requirement: Single-question re-check
The owner SHALL be able to re-check one active question on all 5 engines at most once per question
per PT day, subject to the budget guard.

#### Scenario: Second re-check the same day
- **WHEN** the owner presses Re-check on a question that was already re-checked today (PT)
- **THEN** the request is rejected with 409 and no DataForSEO call is made

### Requirement: Useful weekly Telegram digest
After each scheduled run that produced at least one OK check, the system SHALL send one Telegram
message per business (when `digest_enabled`) with: named counts per engine and change vs the
previous run, questions gained and lost per engine, the 3 places named most often instead of us,
the 3 domains cited most often on questions where we are missing, the owner-set "this week"
actions when present, and spend vs budget. No other AI-visibility Telegram messages are sent.

#### Scenario: Digest after a normal run
- **WHEN** the Monday run for AK.LUX.NAILS finishes with OK checks
- **THEN** one Russian-language message is sent to that business's Telegram chat, without
  em-dashes, ending with the link to the AI tab

#### Scenario: Run produced nothing
- **WHEN** every check in the run failed
- **THEN** no digest is sent and the failure is logged; the next digest mentions the missed week

### Requirement: Internal summary for analysis
The system SHALL expose `GET /api/internal/ai-visibility/{businessId}/summary` and `PUT
/api/internal/ai-visibility/{businessId}/next-actions`, both requiring `X-Internal-Api-Key`.

#### Scenario: Missing key
- **WHEN** the summary endpoint is called without a valid internal API key
- **THEN** the response is 401 and no data is returned
