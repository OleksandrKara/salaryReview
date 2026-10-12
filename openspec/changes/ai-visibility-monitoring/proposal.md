## Why

More clients now ask an AI assistant ("recommend me the best place to do Russian manicure in San
Diego") instead of scrolling Google results. The assistant names 3-5 places, and being one of them
is worth more than ranking #5 on a results page. Today we measure this only with hand-run scripts
on the VPS (`~/seo-monitoring/monthly_report.py`, ChatGPT only, 8 questions, monthly;
`weekly_ai_pmu.py`, PMU only, started 2026-10-12). Results are a Telegram message and a JSON file:
no per-query history, no consistency over time, nothing the owner can open on a phone.

The first weekly run (2026-10-12, PMU, 20 questions) showed why per-engine tracking matters:
Gemini named the studio in 14 of 20 answers, Perplexity in 13, ChatGPT (gpt-4o-mini API) in 1. One
engine is the gap, not the business as a whole, and the fix for each engine is different (ChatGPT
leans on maps/Bing data and review ratings, Gemini on Google data, Perplexity on cited web pages).

The SEO agency (InWeb) showed the owner a paid "AI presence" view on 2026-10-12: about 20 queries,
a green/red tag per engine on each row, last check date, a consistency percentage (named in N of M
checks over time), and a link to the check history. The owner wants the same in the existing owner
dashboard, for both businesses, built cheaply, and wants Claude to use the data to pick the work
that raises consistency.

## What Changes

- **New `ai-visibility.enabled` business feature** and a new **AI tab** at `/owner/marketing/ai`,
  next to the SEO tab, shown only when the feature is on (same rule as the SEO tab).
- **Tracked AI questions per business** (`ai_visibility_query`): up to 20 active per business.
  Claude seeds 20 commercial, recommendation-style questions per business as `PROPOSED`; the owner
  approves, edits, pauses, or removes them in the AI tab. Only `ACTIVE` ones are checked.
- **Weekly check run** (Monday 06:00 PT, ShedLock): every active question is asked once to each of
  5 engines through DataForSEO: ChatGPT (the real chatgpt.com answer, not a cheap API model),
  Google AI Mode, Gemini, Perplexity, Claude. Each answer is stored as one
  `ai_visibility_check` row: named us or not, our rank among the places named, whether our own
  site is cited, which other places were named, which sources were cited, the sub-searches the
  assistant ran, a short answer excerpt, cost, and errors.
- **Consistency**: per question and engine, `named / successful checks`, shown all-time and for
  the last 8 weeks, with the count (for example "92% · 11/12").
- **AI tab UI** (mobile-first): one row per question with 5 engine chips (green = named in the last
  check, red = not named, grey = no data or error), small "checked Oct 12" text, consistency per
  chip, and a History link that opens every past check (date, engine, named or not, places named,
  sources cited, excerpt). Above the table: presence per engine this week vs last week, spend this
  month, next run date. Below it: "Who AI recommends instead" (most-named competitors and most-cited
  sources on questions where we are missing). These two lists are the input for the work Claude
  picks.
- **Marketing tab strip fix**: on a phone every tab (Overview, Contacts, Funnel, Ads Report, LTV,
  SEO, AI) is visible without horizontal scrolling (the tabs wrap into rows instead of an
  `overflow-x-auto` strip).
- **Weekly Telegram digest** per business after the run, built to be useful: what changed (gained
  or lost questions per engine), the 3 places AI names instead of us most often, the 3 sources
  cited most where we are absent, and the 1-3 tasks Claude will work on this week. No per-check
  alerts, no daily messages (owner choice 2026-10-12).
- **Budget guard**: a monthly spend cap per business (default $8, about 2.5x the expected spend);
  the run stops before a call that would exceed it and says so in the digest.
- **Page comparison (what competitors' pages have and ours lack)**: each question gets a target
  page on our site (seeded by Claude, editable). Once a month the system fetches our target pages
  and the competitor pages the AI engines cited or named for the same questions, and detects a
  fixed list of page components (before/after photos, FAQ, visible prices, reviews on the page,
  stated rating, artist bio and years of experience, process steps, aftercare, video, booking
  button, financing, map and address, schema types, press mentions, guarantee/touch-up policy,
  update date, word count). A component that most competitor pages for a question have and our
  target page lacks becomes a **page gap**, shown per question and in a summary panel, and the top
  gaps go into the weekly digest. Fetching is done by our own server (free), with DataForSEO
  On-Page as a cheap fallback for pages that block plain requests.
- **Competitor shortcut**: a place AI names often can be added to the existing SEO competitor list
  in one click (that list already tracks competitor PageSpeed).
- **Internal read endpoint** (`X-Internal-Api-Key`) returning the same summary as JSON, so Claude
  can analyze the data from the server without the UI.
- **Retire the VPS scripts' AI parts** once the backend run has 2 clean weeks: remove the
  ChatGPT block from `monthly_report.py` and the `seo-weekly-ai-pmu` timer, so nothing is paid for
  twice.

## Cost

Measured 2026-10-12 on real calls: ChatGPT (chatgpt.com scraper) $0.004, Google AI Mode $0.004,
Perplexity (sonar) $0.004, Gemini (gemini-3.5-flash) $0.03, Claude (claude-haiku-4-5) $0.024 per
question. 40 questions (20 × 2 businesses) × 5 engines weekly is about $2.64 per run, about
$11.50 per month for both businesses. Page comparison adds almost nothing: direct fetches are free,
the On-Page fallback costs $0.00015-0.0015 per page (measured), capped at 40 pages per business per month.

## Non-goals

- No daily or on-demand bulk checks (owner chose weekly). A single-question re-check button is in
  scope (one question × 5 engines, about $0.07, limited to once per question per day).
- No automatic changes to either website. Tasks Claude picks from this data go through the normal
  PR flow, and pmu-annakara.com work stays under GROWTH_PLAN.md section 0 (no rewrites of
  protected pages, small batches, owner sees text first).
- Page gaps are suggestions, never automatic edits. Filling a gap must use real material only
  (real before/after photos with client consent, FAQ answers from facts the owner confirmed, real
  reviews), and on pmu-annakara.com only as additions under GROWTH_PLAN.md section 0.
- No copying of competitor text; only the presence of components is compared.
- No LLM-based answer analysis (mention detection, competitor and citation extraction are
  deterministic parsing, so a check costs only the DataForSEO call).
- No public sharing of the AI tab, no per-staff access (OWNER only).
- Not a replacement for the monthly Google rankings report (`monthly_report.py` keeps its SERP
  and review parts).

## Verification

- Backend unit tests: mention detection (aliases, domains, case, false positives like "Anna's
  Nails"), rank extraction, citation and competitor parsing on saved real DataForSEO responses for
  each of the 5 engines (fixtures from the 2026-10-12 probe runs, PII-free), consistency math,
  budget guard, ShedLock run, business scoping of every id-taking repository method.
- Integration test: a run with a stubbed DataForSEO client writes the expected rows for 2
  businesses and never crosses tenants.
- Frontend: tsc/build in CI; Playwright check on the deployed site at 390px and 1280px widths that
  all 7 marketing tabs are visible without horizontal scroll and the AI table and History sheet
  render.
- Live: first scheduled run on the following Monday; compare its numbers with the VPS script for
  the same week, then retire the script (task 9).
