## 1. Backend — ad spend schema

- [ ] 1.1 Create `V47__ad_spend_entries.sql` — new `ad_spend_entries` table (see design.md D2),
      migrate every existing `ad_spend` row forward as one whole-month `mani` entry (design.md D5),
      drop `ad_spend`
- [ ] 1.2 Create `AdSpendEntry` entity + `AdSpendEntryRepository` (find-overlapping-range query by
      `landing_page_slug`), delete `AdSpend`/`AdSpendRepository`
- [ ] 1.3 Create `AdSpendResolver` (or a method on `MarketingAnalyticsService`) implementing the
      prorate-and-sum-with-exact-flag logic from design.md D2 — one function used by every period
      kind (WEEK/MONTH_TO_DATE/MONTH/CUSTOM)

## 2. Backend — follow-up appointments, page-scoped

- [ ] 2.1 Extract the per-contact Square resolution logic already inside
      `countFollowUpBookingsByVariant`/`hasUncountedRealAppointment` into a shared private helper
- [ ] 2.2 Add `MarketingContactsService.followUpAppointments(landingPageSlug, statsSince,
      attributedBookingIds): List<Appointment>` (page-scoped, not grouped by variant) built on that
      shared helper — `countFollowUpBookingsByVariant` keeps its exact current behavior/callers
- [ ] 2.3 Confirm (via test) a booking already in `attributedBookingIds` never appears in this
      list — no double-counting against the "immediate" path

## 3. Backend — adsReport period kinds + follow-up merge

- [ ] 3.1 Add `periodKind` (`WEEK | MONTH_TO_DATE | MONTH | CUSTOM`) to `adsReport(...)`, replacing
      the current `weekly: boolean` flag; `MONTH_TO_DATE` produces one unbucketed `[1st-of-month,
      today]` row (design.md D3), not routed through `buildPeriods`
- [ ] 3.2 Add `monthInProgress: boolean` to `PeriodRow` (`periodEnd.isAfter(today)`) for the
      frontend's "in progress" badge
- [ ] 3.3 Merge `followUpAppointments(...)` results into `adsReport`'s `inRange`/`completed`/
      `upcoming` before bucketing (design.md D4); add a `customersFollowedUp`/similar count to
      `PeriodRow` alongside the existing `customersCreated`
- [ ] 3.4 Wire the new `AdSpendResolver` into `adsReport` in place of `adSpendFor`/
      `prorateWeeklySpend`, scoped by the report's `slug`

## 4. Backend — controller routes

- [ ] 4.1 Update `MarketingAdsReportController`: `period` param accepts `week|mtd|month|custom`;
      `custom` requires explicit `from`/`to` (no default-range fallback, since a caller-specified
      range is the whole point)
- [ ] 4.2 New `POST /api/owner/marketing/ads-report/spend` (create an `ad_spend_entries` row:
      `slug`, `periodStart`, `periodEnd`, `amount`) and `GET .../spend?slug=` (list recent entries
      for the entry-management UI) — OWNER-only write, matching today's ad-spend write gating
- [ ] 4.3 Backend unit tests: prorate math (whole-month exact, partial-week estimated, two
      overlapping entries), month-to-date boundary (does not leak into next month), follow-up
      merge (no double count), controller period-param parsing

## 5. Frontend — Ads Report page

- [ ] 5.1 Add `recharts` dependency
- [ ] 5.2 Period-type control gains "Month to date"; period-table gains visits/clicks/leads/
      immediate/follow-up/unbooked columns and the ad-spend "≈" estimated marker
- [ ] 5.3 View switcher: Table / Text / Chart (segmented control, matches existing
      `PeriodTypeButton` visual language)
- [ ] 5.4 `formatWhatsAppReport(...)` pure function (design.md D7) + `<pre>` block + Copy button
      (Clipboard API, matches this session's manual report format exactly)
- [ ] 5.5 Chart view (Recharts, Full Month period only — hidden, not disabled, for other period
      kinds): ad spend / revenue collected / anticipated revenue per month
- [ ] 5.6 Ad-spend entry form: page selector (defaults to whichever page's report is open), period
      (quick presets: This week / This month / Month-to-date-so-far / Custom), amount
- [ ] 5.7 "View breakdown" drill-down: segments (all/fresh/returning) + completed/upcoming lists,
      ported from `AnalyticsView.tsx`, scoped to the currently-displayed period/range, including
      follow-up appointments

## 6. Frontend — Analytics tab removal + shared Sync button

- [ ] 6.1 Delete `AnalyticsView.tsx`, its route, and its `AdminMenu`/`MarketingTabs` nav entry
- [ ] 6.2 Move the "Sync appointments" button from `ContactsFilterBar` into `MarketingTabs.tsx`'s
      shared header (visible on Overview/Funnel/Ads Report/Contacts)
- [ ] 6.3 Update `api.ts`/`serverApi.ts`: remove `setAdSpend(year, month, amount)`, add the new
      ad-spend-entry create/list calls and the updated `adsReport` params

## 7. Verification

- [ ] 7.1 `mvn test` — all new + existing tests pass
- [ ] 7.2 `tsc`/`eslint` clean on the frontend
- [ ] 7.3 Manual: re-derive this session's manual mani report (5–19 Jul 2026) from the new Ads
      Report UI (Custom period) and confirm visits/clicks/leads/immediate/follow-up/unbooked and
      revenue-collected/anticipated match what was hand-built; confirm the WhatsApp text view
      matches the format used in this session
- [ ] 7.4 Confirm Analytics tab's removal doesn't drop any capability: segments, completed list,
      upcoming list all reachable via Ads Report's drill-down
