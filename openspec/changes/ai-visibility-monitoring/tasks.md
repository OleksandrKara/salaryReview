## 1. Backend: schema and seed

- [ ] 1.1 `V167__ai_visibility.sql`: tables `ai_visibility_settings`, `ai_visibility_query`,
      `ai_visibility_run`, `ai_visibility_check` + indexes exactly as design.md D2 (unique index on
      `(business_id, lower(query_text))`)
- [ ] 1.2 Same migration: `business_feature` rows `ai-visibility.enabled = false` for businesses 1
      and 2; `ai_visibility_settings` for both:
      - 1 AK.LUX.NAILS: aliases `AK.LUX.NAILS`, `AK Lux Nails`, `AKLuxNails`, `AK Lux`; domains
        `akluxnails.com`; budget 8.00
      - 2 PMU: aliases `Anna Kara`, `Anna Kara's Permanent Makeup Studio`, `Anna Kara's Beauty PMU
        Studio`, `PMU Anna Kara`, `Anna Kara Academy`; domains `pmu-annakara.com`; budget 8.00
- [ ] 1.3 Same migration: 20 `PROPOSED` questions per business (owner approves in the UI; topic in
      brackets):
      - **AK.LUX.NAILS**: recommend me the best place to do Russian manicure in San Diego [russian
        manicure]; best Russian manicure in San Diego [russian manicure]; who does the most precise
        dry e-file cuticle manicure in San Diego [russian manicure]; how much is a Russian manicure
        in San Diego and where is it worth it [russian manicure]; best place for a Russian pedicure
        in San Diego [pedicure]; best pedicure in downtown San Diego [pedicure]; best nail salon in
        downtown San Diego [salon]; highest rated nail salon in San Diego [salon]; best nail salon
        near the Gaslamp Quarter San Diego [salon]; best nail salon in Bankers Hill or Hillcrest
        San Diego [salon]; luxury nail salon in San Diego with great reviews [salon]; where to get
        gel nail extensions without acrylic in San Diego [extensions]; best gel nail extensions in
        San Diego [extensions]; gel manicure that lasts 3-4 weeks in San Diego [gel]; best
        builder gel or gel overlay manicure in San Diego [gel]; best Japanese manicure in San Diego
        [natural nails]; where to repair broken or split nails in San Diego [natural nails]; nail
        salon with sterilized tools and the best hygiene in San Diego [hygiene]; nail salon that
        does manicure and pedicure at the same time (4 hands) in San Diego [service]; best men's
        manicure in San Diego [service]
      - **PMU**: best place with highest rating for permanent makeup in San Diego [general];
        recommend the best permanent makeup artist in San Diego [general]; most experienced
        permanent makeup artist in San Diego [general]; best permanent makeup studio in downtown San
        Diego [general]; how much does permanent makeup cost in San Diego and who is worth it
        [general]; permanent makeup with payment plans or financing in San Diego [general];
        recommend the best microblading artist in San Diego [brows]; best powder or ombre brows in
        San Diego [brows]; best nano brows hair strokes in San Diego [brows]; who can fix bad
        microblading or an old eyebrow tattoo in San Diego [brows]; natural looking eyebrow tattoo
        for thin brows or alopecia in San Diego [brows]; best lip blush tattoo artist in San Diego
        [lips]; best permanent eyeliner artist in San Diego [eyes]; lash line enhancement tattoo in
        San Diego [eyes]; best scar camouflage tattoo in San Diego [paramedical]; stretch mark
        camouflage tattoo in San Diego [paramedical]; 3D areola tattoo after mastectomy in San Diego
        [paramedical]; best BB glow facial in San Diego [skin]; permanent makeup for mature skin in
        San Diego [general]; best permanent makeup training academy in San Diego [academy]

## 2. Backend: domain, repositories, client

- [ ] 2.1 Entities + repositories for the 4 tables; every method taking a caller-controlled id is
      `...AndBusinessId` (`findByIdAndBusinessId`, `findByBusinessIdAndStatusIn`, ...)
- [ ] 2.2 `AiVisibilityProperties` (`ai-visibility.*`: engine → endpoint, model, expected cost;
      DataForSEO login/password from `DATAFORSEO_LOGIN`/`DATAFORSEO_PASSWORD`); compose env
      passthrough in `docker-compose.yml` and deploy compose; values added to `~/salaryReview/.env`
      from `~/seo-monitoring/credentials/dataforseo.json` on the server only
- [ ] 2.3 `DataForSeoClient` (RestClient, Basic auth, retries per design.md D1, returns status +
      cost + raw JSON)
- [ ] 2.4 `BusinessFeatureService.AI_VISIBILITY_ENABLED = "ai-visibility.enabled"`; `MeController`
      features map gains `aiVisibilityEnabled`

## 3. Backend: parsing

- [ ] 3.1 `AiAnswerParser` per engine (D4) → `ParsedAnswer{text, citations, subQueries}`
- [ ] 3.2 `MentionDetector` (aliases, own domains, places named, rank, excerpt around mention)
- [ ] 3.3 Test fixtures: one saved real response per engine from the 2026-10-12 probes (stripped to
      the fields used, no PII), plus hand-written edge cases (punctuation variants, "Anna's Nails",
      cited-but-not-named, Gemini redirect URLs, AI Mode with no AI block)

## 4. Backend: run, budget, re-check

- [ ] 4.1 `AiVisibilityRunService.runForBusiness(businessId, trigger, queryIds)`: run row, budget
      guard per call, sequential calls, check rows, 3-consecutive-failure engine skip, totals
- [ ] 4.2 `AiVisibilityScheduler` (Mon 06:00 PT, ShedLock `AiVisibilityScheduler_run`,
      lockAtMostFor PT2H, per-business runAs + try/catch)
- [ ] 4.3 Re-check: async single-question run, once per question per PT day (409), budget guard

## 5. Backend: summaries, API, security

- [ ] 5.1 `AiVisibilityQueryService`: overview (cells, consistency all/8w with n/N, per-engine week
      totals vs previous run, month spend), history per question, "instead" lists (D5)
- [ ] 5.2 `OwnerAiVisibilityController` (`/api/owner/marketing/ai/**`) and settings endpoints (D6);
      404 when the feature is off
- [ ] 5.3 `InternalAiVisibilityController` (summary GET, next-actions PUT, `X-Internal-Api-Key`)
- [ ] 5.4 SecurityConfig: `/api/owner/marketing/ai/**`, `/api/owner/settings/ai-visibility` → OWNER

## 6. Backend: weekly digest

- [ ] 6.1 `AiVisibilityDigestService`: builds the D8 message (Russian, no em-dashes) from the run
      just finished vs the previous run; sends through the business's Telegram config; skipped
      when `digest_enabled = false` or the run has 0 OK checks; flags "parser drift" when more than
      half of OK checks have no places named
- [ ] 6.2 Called at the end of each SCHEDULED run (not for re-checks)

## 7. Frontend

- [ ] 7.1 `MarketingTabs.tsx`: wrapping tab layout (4-column grid under `sm`, wrapped row above),
      AI tab after SEO when `aiVisibilityEnabled`; remove the overflow-x strip and update its comment
- [ ] 7.2 Proxy routes under `app/api/owner/marketing/ai/**` and `app/api/owner/settings/ai-visibility`;
      `api.ts` methods; types in `lib/types.ts`
- [ ] 7.3 `/owner/marketing/ai/page.tsx` + `loading.tsx`: header cards, desktop table, mobile cards,
      topic groups, chips with consistency and dates, "instead" panel
- [ ] 7.4 History sheet (bottom sheet on mobile, side panel on desktop): weekly dot grid + check list,
      lazy-loaded
- [ ] 7.5 Suggested-questions block (Approve / Edit / Remove / Approve all), add-question field with
      20-limit counter, Re-check button with pending state and the 409 message
- [ ] 7.6 Settings card (aliases, domains, budget, digest on/off) on the existing owner settings page

## 8. Tests and verification

- [ ] 8.1 Unit: parser per engine, mention detector edge cases, consistency math (n < 3 hides %),
      budget guard, engine-skip after 3 failures, digest text (snapshot, no em-dash)
- [ ] 8.2 Integration (Testcontainers pgvector/pgvector:pg16): stubbed client run for 2 businesses,
      rows scoped per business; cross-tenant PATCH/recheck → 404; feature off → 404; internal
      endpoints without key → 401
- [ ] 8.3 CI green (backend suite + frontend tsc/build), merge, deploy
- [ ] 8.4 After deploy: enable `ai-visibility.enabled` for both businesses; owner approves the
      questions; one manual re-check of one question per business to confirm real calls, parsing and
      spend (about $0.14); delete those test runs' rows afterwards if the owner prefers a clean
      first week
- [ ] 8.5 Playwright on production at 390px and 1280px: all 7 marketing tabs visible without
      horizontal scroll; AI table, mobile cards and History sheet render

## 9. Cut over from the VPS scripts

- [ ] 9.1 Optional backfill: import the 2026-10-12 PMU Gemini and Perplexity results from
      `~/seo-monitoring/data/ai-weekly/2026-10-12.json` as one `SCHEDULED` run (ChatGPT rows
      skipped: different model)
- [ ] 9.2 After 2 clean weekly runs: disable `seo-weekly-ai-pmu.timer`, remove the ChatGPT block
      from `monthly_report.py`, update `~/seo-monitoring/RUNBOOK.md`
- [ ] 9.3 Weekly routine (Claude): every Monday after the digest, read the internal summary for both
      businesses, pick 1-3 tasks that target the biggest gaps (an engine where we are missing on many
      questions, a source cited often where we are absent, a competitor named often), set
      `next-actions`, record them in `~/business-growth/GROWTH_PLAN.md`; PMU site work only within
      GROWTH_PLAN.md section 0. Claude has no durable scheduler of its own, so this runs in the Monday
      session (or via the persistent claude-persist session if the owner wants it automatic)
