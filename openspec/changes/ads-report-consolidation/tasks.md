## 1. Backend — ad spend schema

- [x] 1.1 Create `V47__ad_spend_entries.sql` — new `ad_spend_entries` table (see design.md D2),
      migrate every existing `ad_spend` row forward as one whole-month `mani` entry (design.md D5),
      drop `ad_spend`
- [x] 1.2 Create `AdSpendEntry` entity + `AdSpendEntryRepository` (find-overlapping-range query by
      `landing_page_slug`), delete `AdSpend`/`AdSpendRepository`
- [x] 1.3 Create `AdSpendResolver` (or a method on `MarketingAnalyticsService`) implementing the
      prorate-and-sum-with-exact-flag logic from design.md D2 — one function used by every period
      kind (WEEK/MONTH_TO_DATE/MONTH/CUSTOM)

## 2. Backend — follow-up appointments, page-scoped

- [x] 2.1 Extract the per-contact Square resolution logic already inside
      `countFollowUpBookingsByVariant`/`hasUncountedRealAppointment` into a shared private helper
- [x] 2.2 Add `MarketingContactsService.followUpAppointments(landingPageSlug, statsSince,
      attributedBookingIds): List<Appointment>` (page-scoped, not grouped by variant) built on that
      shared helper — `countFollowUpBookingsByVariant` keeps its exact current behavior/callers
- [x] 2.3 Confirm (via test) a booking already in `attributedBookingIds` never appears in this
      list — no double-counting against the "immediate" path

## 3. Backend — adsReport period kinds + follow-up merge

- [x] 3.1 Add `periodKind` (`WEEK | MONTH_TO_DATE | MONTH | CUSTOM`) to `adsReport(...)`, replacing
      the current `weekly: boolean` flag; `MONTH_TO_DATE` produces one unbucketed `[1st-of-month,
      today]` row (design.md D3), not routed through `buildPeriods`
- [x] 3.2 Add `monthInProgress: boolean` to `PeriodRow` (`periodEnd.isAfter(today)`) for the
      frontend's "in progress" badge
- [x] 3.3 Merge `followUpAppointments(...)` results into `adsReport`'s `inRange`/`completed`/
      `upcoming` before bucketing (design.md D4); add a `customersFollowedUp`/similar count to
      `PeriodRow` alongside the existing `customersCreated`
- [x] 3.4 Wire the new `AdSpendResolver` into `adsReport` in place of `adSpendFor`/
      `prorateWeeklySpend`, scoped by the report's `slug`

## 4. Backend — controller routes

- [x] 4.1 Update `MarketingAdsReportController`: `period` param accepts `week|mtd|month|custom`;
      `custom` requires explicit `from`/`to` (no default-range fallback, since a caller-specified
      range is the whole point)
- [x] 4.2 New `POST /api/owner/marketing/ads-report/spend` (create an `ad_spend_entries` row:
      `slug`, `periodStart`, `periodEnd`, `amount`) and `GET .../spend?slug=` (list recent entries
      for the entry-management UI) — OWNER-only write, matching today's ad-spend write gating
- [x] 4.3 Backend unit tests: prorate math (whole-month exact, partial-week estimated, two
      overlapping entries), month-to-date boundary (does not leak into next month), follow-up
      merge (no double count), controller period-param parsing

## 5. Frontend — Ads Report page

- [x] 5.1 Add `recharts` dependency
- [x] 5.2 Period-type control gains "Month to date"; period-table gains the ad-spend "≈"
      estimated marker, a follow-up count, and an "in progress" badge (`monthInProgress`) — no
      visits/clicks/leads/unbooked columns, see design.md D7 scope note (fast-follow, not this change)
- [x] 5.3 View switcher: Table / Text / Chart (segmented control, matches existing
      `PeriodTypeButton` visual language)
- [x] 5.4 `formatWhatsAppReport(...)` pure function (design.md D7) + `<pre>` block + Copy button
      (Clipboard API) — money/ROI/customers/follow-ups block style, no funnel section (scope note)
- [x] 5.5 Chart view (Recharts, Full Month period only — hidden, not disabled, for other period
      kinds): ad spend / revenue collected / anticipated revenue per month
- [x] 5.6 Ad-spend entry form: page selector (defaults to whichever page's report is open), period
      (quick presets: This week / This month / Month-to-date-so-far / Custom), amount
- [x] 5.7 "View breakdown" drill-down: segments (all/fresh/returning) + completed/upcoming lists,
      ported from `AnalyticsView.tsx`, scoped to the currently-displayed period/range, including
      follow-up appointments (also required extending `MarketingAnalyticsService.analytics()` itself
      to fold in `followUpAppointments(...)`, which tasks 1-4 hadn't wired up yet — see design.md D6)

## 6. Frontend — Analytics tab removal + shared Sync button

- [x] 6.1 Delete `AnalyticsView.tsx`, its route, and its `MarketingTabs` nav entry
- [x] 6.2 Move the "Sync appointments" button from `ContactsFilterBar` into `MarketingTabs.tsx`'s
      shared header (visible on Overview/Funnel/Ads Report/Contacts) — triggers `router.refresh()`
      since MarketingTabs is a sibling of each tab's content, not a parent; each affected client view
      (`ContactsFilterBar`, `AdsReportView`) re-syncs local state from the refreshed prop via
      `useEffect(() => setX(initialX), [initialX])`
- [x] 6.3 Update `api.ts`/`serverApi.ts`: remove `setAdSpend(year, month, amount)`, add the new
      ad-spend-entry create/list calls and the updated `adsReport` params

## 7. Verification

- [x] 7.1 `mvn test` — all new + existing tests pass (366 tests; the only failure is
      `SalonreviewApplicationTests.contextLoads`, which needs a live Postgres unavailable in this
      sandbox — pre-existing/environment-only, unrelated to this change)
- [x] 7.2 `tsc`/`eslint` clean on the frontend
- [ ] 7.3 Manual: re-derive this session's manual mani report (5–19 Jul 2026) from the new Ads
      Report UI (Custom period) — descoped to money/ROI/customers/follow-ups only, since
      visits/clicks/leads/unbooked aren't wired up yet (see D7 scope note); not run in this sandbox
      (needs the real, deployed app against production Square/Postgres data)
- [x] 7.4 Confirm Analytics tab's removal doesn't drop any capability: segments, completed list,
      upcoming list all reachable via Ads Report's drill-down
