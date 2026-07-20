## Why

Manually building last week's (and then the month-to-date's) Meta Ads report for mani by hand —
querying Postgres directly, then live-checking Square per contact for manager-follow-up bookings,
then correcting for two silently-cancelled appointments the dashboard doesn't surface — took real,
repeated back-and-forth this session to get right, and every number in it should already have been
one click away on `/owner/marketing/ads-report`. Three concrete gaps caused that:

1. **Manager-closed bookings are invisible everywhere except the Overview tab's per-variant
   total.** `MarketingContactsService.countFollowUpBookingsByVariant` (the live Square check that
   finds "a lead who never finished the tracked flow, but a manager booked them by phone") only
   feeds `VariantTable`'s `+N follow-up` line. `Ads Report` and `Analytics` never call it — a
   report built from either today silently undercounts real, paying customers.
2. **Ad spend is one number a month**, entered on the Analytics tab (`ad_spend` table, unique
   `(year, month)`). The owner's ad budget genuinely varies week to week; today the only way to
   reflect that is prorating one blended monthly figure evenly across its days
   (`prorateWeeklySpend`) — which is wrong on any week where spend wasn't actually flat, and
   impossible to correct because there's nowhere to enter a real weekly (or month-to-date, or
   partial) figure instead.
3. **Analytics and Ads Report are two separate tabs answering overlapping questions** (gross
   revenue, customer counts, completed/upcoming appointments) at two different granularities, and
   neither is quite the "how did this week/month of ad spend do" report the owner actually reaches
   for.

## What Changes

- **Ad spend becomes a per-page, per-period ledger** instead of one blended monthly number:
  new `ad_spend_entries` table (`landing_page_slug`, `period_start`, `period_end`, `amount_spent`,
  `entered_by`, `entered_at`), replacing the single-row-per-month `ad_spend` table. The owner can
  enter a real figure for any range — a week, a full month, or "1st of this month through today"
  — for whichever landing page (`mani`/`home`) it was actually spent on. Any report period's spend
  is computed by summing entries that fall fully inside it and prorating (by calendar-day overlap)
  any entry that only partially overlaps — the same math `prorateWeeklySpend` already does today,
  generalized to arbitrary entries instead of only whole-month rows. A period's spend is flagged
  `exact` only when it's covered by non-overlapping, exactly-fitting entries; otherwise `estimated`
  (mirrors the existing `PeriodRow.adSpendEstimated` flag).
- **Ads Report gains manager-follow-up bookings.** `MarketingContactsService` gets a new
  landing-page-scoped (not per-variant) method returning real, non-cancelled Square appointments
  for the page's ads-attributed contacts that `marketing.attribution` doesn't know about — same
  live Square lookup `countFollowUpBookingsByVariant` already does, reused rather than duplicated,
  no new background job (see design.md D1 for why an on-demand model was chosen over a scheduled
  cache). Their revenue folds into the same `revenueCollected`/`anticipatedRevenue` figures
  `adsReport` already computes, and their count folds into `customersCreated`.
- **Four report periods**, all scoped to whichever single landing page is selected (no per-variant
  breakdown — that's `VariantTable`'s job, not this report's):
  - **Week** — Monday through Sunday inclusive (already supported by `adsReport(weekly=true)`).
  - **Month-to-date** — 1st of the current month through today. New: today's `adsReport` only
    knows whole calendar weeks/months, never a partial "so far this month" window.
  - **Full month** — a complete calendar month, viewable (labeled "in progress") before the month
    ends so month-over-month history builds incrementally as each month closes.
  - **Custom** — arbitrary from/to (already supported).
- **Three views per report**: a table (spend, visits, clicks, leads, immediate/follow-up/
  unbooked counts, revenue collected/anticipated, ROI), a WhatsApp-ready plain-text block (exact
  format used in this session's manual reports) with a one-tap copy button, and — for the Full
  Month period specifically — a trend chart (Recharts) across the page's month-over-month history.
- **The "Sync appointments" button moves** from the Contacts tab into `MarketingTabs`' shared
  header, visible on every marketing tab (Overview/Funnel/Ads Report/Contacts) rather than only
  Contacts, since Ads Report's follow-up detection depends on the same phone→Square-customer link
  cache that button refreshes.
- **The Analytics tab is removed.** Its unique value — customer segments (all/fresh/returning),
  the completed-appointments list, and the upcoming-appointments list — becomes a drill-down (a
  "View breakdown" expansion) on the Ads Report page for whichever period/range is currently shown,
  now also including manager-follow-up appointments. `MarketingAnalyticsController`'s endpoint and
  `MarketingAnalyticsService.analytics()` stay as they are — only the frontend page and nav entry
  go away; the drill-down calls the same data.

## Non-goals

- No Meta/Google Ads API integration — spend is still typed in by the owner, just with more
  flexible periods and per-page attribution.
- No change to how `marketing.attribution`/`marketing.contacts` are written (mani/akluxnails-home
  side) — this is a salaryReview-only reporting change.
- No new scheduled job for follow-up detection — accepted as on-demand (see design.md D1); the
  existing `SquareClient` per-customer/window cache (2 min) and bounded concurrency (6 calls)
  already in production are the latency mitigation, not new infrastructure.
- No historical backfill of `ad_spend_entries` from the old `ad_spend` rows beyond a straight
  1:1 migration (each existing monthly row becomes one whole-month entry, unscoped to a page until
  the owner corrects it — see design.md D5).
- No variant-level breakdown inside Ads Report — that already exists on `/owner/marketing`
  (`VariantTable`) and stays there, unchanged.

## Capabilities

### Modified Capabilities

- `marketing-dashboard`: ad spend moves from a single monthly figure to a per-page, per-period
  entry ledger; Ads Report gains a month-to-date period, a WhatsApp-text view, a monthly trend
  chart, and manager-follow-up bookings in its revenue/customer figures; the Analytics tab is
  removed and its segment/completed/upcoming breakdown becomes a drill-down inside Ads Report; the
  Contacts-tab-only "Sync appointments" action becomes available from every marketing tab.

## Impact

- **Backend (salaryReview)**: new `V47__ad_spend_entries.sql` migration (new table + data-migrate
  existing `ad_spend` rows, see design.md D5); new `AdSpendEntry` entity/repository replacing
  `AdSpend`/`AdSpendRepository`; `MarketingAnalyticsService.adsReport()` gains a
  month-to-date period kind, per-page prorated spend lookup, and merged follow-up appointments;
  new `MarketingContactsService.followUpAppointments(landingPageSlug, statsSince,
  attributedBookingIds)` (page-scoped sibling of the existing per-variant
  `countFollowUpBookingsByVariant`, returning full appointment records not just a count);
  `MarketingAdsReportController` gains `period=mtd` and an ad-spend-entry CRUD sub-route;
  `AnalyticsController`/`MarketingAnalyticsService.analytics()` unchanged (still backs the new
  drill-down).
- **Frontend (salaryReview)**: `AnalyticsView.tsx` and its route/nav entry are deleted;
  `AdsReportView.tsx` gains the month-to-date period tab, table/text/chart view switcher, a
  WhatsApp-format copy-to-clipboard block, a breakdown drill-down (segments + completed/upcoming
  lists, ported from `AnalyticsView`), and a new ad-spend-entry form (page + period + amount).
  `MarketingTabs.tsx` gains a shared "Sync appointments" button in its header. New dependency:
  `recharts`.
- **Dependencies**: `recharts` (frontend only).
- **Verification**: backend unit tests for the prorate/exact-vs-estimated spend math (whole-month,
  partial-week, and overlapping-entries cases), for the month-to-date period boundary, and for
  follow-up appointments folding into revenue/customer counts without double-counting an already-
  attributed booking. Frontend `tsc`/`eslint`. Manual check against real data: re-derive this
  session's manually-built mani report (5–19 Jul, 25 leads, 12 immediate + 4 follow-up, 2 silently
  cancelled) from the new Ads Report UI and confirm the numbers match.
