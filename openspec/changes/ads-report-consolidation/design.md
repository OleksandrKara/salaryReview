## Context

This design was written after manually reconstructing a real mani/Meta-Ads report for the owner
in-session (5–19 Jul 2026): SQL against `marketing.contacts`/`marketing.visits`/`marketing.events`/
`marketing.attribution` for visits/clicks/leads/immediate-bookings, then a live Square
`GET /v2/bookings?customer_id=...` sweep (in 30-day windows — Square rejects a wider
`start_at_min`/`start_at_max` range with `BAD_REQUEST`) across every lead's resolved Square
customer ID to find manager-follow-up bookings, which turned up 4 real appointments plus 2 that
the app's own cached data still shows as active but Square now reports `CANCELLED_BY_SELLER`. Every
piece of that manual process maps to an existing (or now-needed) piece of `MarketingAnalyticsService`/
`MarketingContactsService` — this change wires them together properly instead of leaving it a
by-hand exercise.

**Current state, confirmed by direct code reading (not assumed):**
- `MarketingAnalyticsService.adsReport(from, to, sources, slug, weekly)` already does real,
  payroll-matched revenue (`SquareMonthAggregator`/`buildCompletedAppointments` — actually
  collected amounts + real payment channel, not a catalog-price guess) and real upcoming-appointment
  values, bucketed weekly or monthly, already scoped to one landing page (`slug`) and one traffic-
  source set, with no per-variant breakdown. It does **not** count manager-follow-up bookings
  anywhere in its output.
- `buildPeriods` always expands a "month" bucket to the *whole* calendar month
  (`cursor.atDay(1)` .. `cursor.atEndOfMonth()`) regardless of the caller's `to` — so there is no
  way today to get a true month-to-date (partial-month) row; asking for one just returns the whole
  month, correctly reflecting whatever has actually happened/collected so far but silently also
  fetching (harmlessly) data through month-end.
- Ad spend is `ad_spend` — one row per `(year, month)`, entered from the Analytics tab
  (`AdSpendRoi` in `AnalyticsView.tsx` → `POST` via `api.setAdSpend(year, month, amount)`).
  `adsReport`'s weekly rows prorate this single monthly figure evenly across the month's days
  (`prorateWeeklySpend`); monthly rows use it directly (`adSpendFor(YearMonth)`).
- Manager-follow-up detection exists today only as
  `MarketingContactsService.countFollowUpBookingsByVariant(landingPageSlug, statsSince,
  attributedBookingIds)`, feeding `VariantTable`'s `+N follow-up` line on `/owner/marketing`. It:
  resolves each contact's Square customer ID (`raw.squareCustomerId()` or a phone-resolved
  `MarketingContactSquareLink` from the manual "Sync appointments" button), fetches their bookings
  from `since = contact.createdAt()` to `now + FUTURE_BOOKING_HORIZON` via
  `SquareClient.bookingsForCustomer` (which already fans out 30-day windows concurrently, bounded
  to 6 simultaneous Square calls, each window cached 2 minutes), filters out cancelled bookings,
  and counts any booking ID not already in `marketing.attribution`. This is a live Square call
  *every time it's invoked* — there's no persisted cache of the result itself, only of the raw
  per-window HTTP response.
- `MarketingAnalyticsDto` (the Analytics tab's payload) already carries everything the owner asked
  to keep: `Segment` (customerCount/serviceCount/grossRevenue) for all/fresh/returning, a
  `completed` list (real collected $, real channel, per booking) and an `upcoming` list (catalog
  price, per booking) — all already page/source-scoped, never per-variant.
- No charting library exists in `frontend/package.json` today.

## Goals / Non-Goals

**Goals:**
- One page (`Ads Report`) that answers "how did this week/month-to-date/month/custom range of ad
  spend perform" completely, including manager-follow-up bookings and correct per-page spend,
  without the owner ever needing to cross-reference Postgres or Square by hand again.
- Ad spend entry flexible enough to match how the owner actually budgets (varies week to week),
  without requiring a rigid weekly-vs-monthly choice — any period, prorated correctly against
  whatever was actually entered.
- Fold Analytics' unique value (segments, completed/upcoming detail) into Ads Report as a
  drill-down, then delete the now-redundant tab, without losing any of its numbers.

**Non-Goals:**
- No new background/scheduled job for follow-up detection (see D1) — the owner explicitly chose
  to keep the current on-demand "Sync appointments" button model rather than add scheduling
  complexity for fresher-but-still-imperfect data.
- No Meta/Google Ads spend API integration.
- No per-variant breakdown inside Ads Report (stays exclusive to `VariantTable`).

## Decisions

### D1: Follow-up detection stays on-demand (owner-confirmed); button moves to a shared header

**Decision**: No new scheduled job, no new persisted "resolved follow-up bookings" cache table.
`MarketingContactsService` gains a landing-page-scoped method,
`followUpAppointments(landingPageSlug, statsSince, attributedBookingIds)`, built by lifting the
existing per-contact resolution logic out of `countFollowUpBookingsByVariant` into a shared private
helper both methods call — the variant-grouped version keeps its exact current behavior for
`VariantTable`; the new one returns the same `List<Appointment>` shape `fetchAppointments` already
produces (customer id/name, service name, price, start_at, status), unfiltered by variant, for
`adsReport`/`analytics` to fold in.

The existing "Sync appointments" button (`ContactsFilterBar` → `POST
/api/owner/marketing/contacts/sync`) moves into `MarketingTabs.tsx`'s shared header so it's one
click away regardless of which marketing tab is open — Ads Report's follow-up numbers are only as
fresh as the last sync, same staleness contract Overview's `+N follow-up` line already has today.

**Rationale**: The owner was offered a scheduled-sync alternative (every 15/30 min, persisting
results) and explicitly chose to keep today's on-demand model rather than add that complexity.
This is also lower-risk: `SquareClient`'s existing per-customer/window cache (2 min) and bounded
concurrency (6 simultaneous calls, `MAX_CONCURRENT_SQUARE_CALLS`) already exist specifically to
keep this kind of fan-out fast; reusing it rather than building new cache infrastructure means one
less thing that can silently go stale or drift from what Square actually shows (as happened with
the 2 silently-cancelled bookings this session found — a persisted cache without its own
invalidation story could make that worse, not better).

**Consequence**: A report view for a page/range with many leads still costs one round of Square
calls (parallelized, throttled, short-cached) — acceptable per the owner's explicit choice, and no
slower than what `VariantTable`'s `+N follow-up` already costs on every Overview load today.

### D2: Ad spend becomes a per-page, per-period entry ledger

**Decision**: Replace `ad_spend` (`id, year, month, amount_spent, updated_by, updated_at`, unique
`(year, month)`) with `ad_spend_entries`:

```sql
CREATE TABLE ad_spend_entries (
    id BIGSERIAL PRIMARY KEY,
    landing_page_slug TEXT NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL CHECK (period_end >= period_start),
    amount_spent NUMERIC(10,2) NOT NULL,
    entered_by VARCHAR(100),
    entered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ad_spend_entries_page_period ON ad_spend_entries (landing_page_slug, period_start, period_end);
```

No uniqueness constraint on `(landing_page_slug, period_start, period_end)` — the owner may enter,
say, a "this week" figure and later a corrected one; the latest entry whose range exactly matches
wins for "exact" resolution (see below), but overlapping historical entries are kept rather than
silently overwritten, so a correction is auditable.

**Spend resolution for an arbitrary report range `[from, to]` on page `slug`:**
1. Fetch every `ad_spend_entries` row for `slug` overlapping `[from, to]`.
2. For each, compute the overlap in days and prorate: `amount * overlap_days / entry_total_days`
   (generalizes `prorateWeeklySpend`'s per-day-of-month split to arbitrary entries).
3. Sum the prorated contributions.
4. `exact = true` only if the entries overlapping `[from, to]` exactly tile it with no gaps and no
   double-covered days (i.e., the naive sum needed no proration) — otherwise `estimated = true`,
   reusing `PeriodRow.adSpendEstimated`'s existing meaning.

This one function replaces both `adSpendFor(YearMonth)` and `prorateWeeklySpend` — a week, a
month, an MTD range, and a custom range all resolve through the identical code path.

**Rationale for per-page**: the owner confirmed ads should be tracked separately per landing page
now, specifically so a future `home` campaign doesn't get silently blended into `mani`'s numbers
requiring a later untangling. mani-only today, but the schema is right from the start.

**Alternative considered and rejected**: keeping `(year, month)` granularity and just adding a
`landing_page_slug` column. Rejected because it doesn't solve the actual ask — flexible entry
periods (week, MTD, month) — only the page-scoping half of it.

### D3: Month-to-date is a genuinely new period kind, not a clipped MONTH bucket

**Decision**: Add a third period kind alongside today's `WEEK`/`MONTH` —
`MONTH_TO_DATE` — that produces exactly one `PeriodRow` for `[YearMonth.now().atDay(1), today]`,
**not** expanded to end-of-month (unlike `buildPeriods`'s existing MONTH handling). `adsReport`
gains a `periodKind` parameter (`WEEK | MONTH_TO_DATE | MONTH | CUSTOM`); `MONTH`/`CUSTOM` reuse
today's bucketing (`CUSTOM` is just `buildPeriods` over the caller's exact `[from, to]`, already
supported), `MONTH_TO_DATE` is the one new code path (a single unbucketed row, not a loop over
`buildPeriods`).

**"Full month" viewed before the month ends**: per the owner's choice, this is not blocked — the
`MONTH` period kind returns the row (real collected-so-far + real upcoming-for-rest-of-month) with
a `monthInProgress: boolean` field (`periodEnd.isAfter(today)`) the frontend renders as an "in
progress" badge, rather than hiding the report until month close.

### D4: Follow-up appointments fold into existing revenue/customer figures without double-counting

**Decision**: `followUpAppointments(...)` explicitly excludes any booking ID already in
`attributedBookingIds` (same guard `hasUncountedRealAppointment` already applies) — a lead's
*original* tracked booking is never counted twice as both "immediate" and "follow-up". Their
resulting `AttributedService`-shaped rows (built the same way `buildCompletedAppointments`/
`upcomingAppointments` already classify a booking as completed-vs-upcoming, by comparing `start_at`
to today and checking `SquareMonthAggregator`'s payment match) are merged into `inRange`/
`completed`/`upcoming` before period bucketing, so every existing figure (`revenueCollected`,
`anticipatedRevenue`, `customersCreated`, `completedAppointments`) already includes them —
`adsReport`'s per-period math itself does not change, only its input list grows.

**Also folds in the "silently cancelled" finding**: since `followUpAppointments` (like
`hasUncountedRealAppointment`) filters on Square's *live* current status, and the *existing*
immediate-booking path (`buildCompletedAppointments`, sourced from payroll-matched
`SquareMonthAggregator` data) already only counts appointments that actually collected money — a
cancelled appointment never had a payroll line, so it was never silently counted as
`revenueCollected` even before this change. The 2 cancelled bookings this session found were only
ever wrongly counted in `bookingsCompleted`-style *counts* (a value `adsReport` does not currently
expose at all — `customersCreated` counts fresh Square customers, not raw booking existence), so no
additional fix is needed here beyond `followUpAppointments` itself correctly excluding cancelled
statuses, which it already does by construction.

### D5: Migration from the old `ad_spend` table

**Decision**: `V47__ad_spend_entries.sql` creates `ad_spend_entries` and copies every existing
`ad_spend` row forward as one whole-month entry: `landing_page_slug = 'mani'` (the only page with
real ad spend history today — see proposal.md's Non-goals), `period_start =
make_date(year,month,1)`, `period_end = (make_date(year,month,1) + interval '1 month' - interval
'1 day')::date`, carrying `amount_spent`/`updated_by → entered_by`/`updated_at → entered_at`
forward unchanged. The old `ad_spend` table is dropped in the same migration — nothing else reads
it once `AdSpendRepository`/`AdSpend` are deleted in the same change.

### D6: Analytics tab removal — drill-down replaces the top-level page

**Decision**: `MarketingAnalyticsController`/`MarketingAnalyticsService.analytics()` are untouched
— still callable, still correct. `AnalyticsView.tsx` (the page) and its nav entry are deleted.
Ads Report gains a "View breakdown" toggle (per the currently-displayed period/range) that calls
`analytics()` for that exact `[from, to]` and renders the segments (all/fresh/returning) +
completed/upcoming lists inline — the same data, ported component, one level down instead of a
separate tab. `followUpAppointments` results are folded into this drill-down's completed/upcoming
lists too, for consistency with the summary numbers above it.

### D7: Views — table, WhatsApp text, chart

**Scope note (owner-confirmed after tasks 1–4 were built):** the manual report's visits/clicks/
leads/unbooked figures come from `marketing.contacts`/`visits`/`events` (the Funnel capability),
which today only returns one whole-range snapshot (`FunnelDashboardDto`), not a per-week/per-month
breakdown — `adsReport`'s `PeriodRow` was never extended to carry them. Rather than block the
frontend on new Funnel-bucketing backend work, the owner chose to ship Table/Text/Chart now using
exactly what `PeriodRow` already returns (ad spend, revenue collected/anticipated, customers
created/completed, follow-ups, ROI/ROAS derived client-side); visits/clicks/leads/unbooked are a
tracked fast-follow, not part of this change.

**Table**: extends today's `PeriodTable` with an ad-spend cell showing the resolved amount plus an
"≈" marker when `estimated`, plus a follow-up count next to customers created, and an "in progress"
badge on a Full Month row still mid-month (`monthInProgress`).

**WhatsApp text**: a `formatWhatsAppReport(...)` pure function producing the same block style used
in this session's manual reports — money (already-collected / anticipated / total), ROI (realized
vs. total ROAS/ROI%), customers created vs. follow-ups, cost-per-customer — single-asterisk
WhatsApp bold markers, no markdown tables, no funnel section (see scope note above). Rendered
inside a `<pre>` with a "Copy" button (`navigator.clipboard.writeText`) — matches the
copy-paste-ready ask exactly for the data that's actually available.

**Chart** (Recharts, Full Month period only): one line/bar combo chart — ad spend, revenue
collected, and anticipated revenue per month, x-axis = month, so the owner can see trend
direction month over month once a few months of history exist. Only rendered for `MONTH` period
kind (a single week or MTD row has nothing to trend against).

**Mobile/web UX**: view switcher (Table/Text/Chart) as a small segmented control next to the
existing period-type buttons; Chart view is hidden entirely (not just disabled) outside `MONTH`
period kind rather than shown empty, matching this codebase's established "don't render a feature
that has nothing to show" convention (e.g. `FollowUpExplainer`, the inactive-variants collapse).

## Migration

New Flyway migration: **V47** (`ad_spend_entries.sql`) — see D2/D5. No `SecurityConfig` changes:
the new ad-spend-entry endpoints live under `/api/owner/marketing/**`, already OWNER+ADS_MANAGER
gated for GET / OWNER-only for writes, matching the existing ad-spend write rule
(`PUT /api/owner/marketing/analytics/ad-spend` today).

## Open Questions

None outstanding — the four decisions that needed the owner's input (follow-up latency model,
ad-spend page-scoping, mid-month "Full Month" visibility, chart library) were confirmed directly
before writing this design (D1, D2, D3, D7).
