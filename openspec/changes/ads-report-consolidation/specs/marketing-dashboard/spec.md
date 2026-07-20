## ADDED Requirements

### Requirement: Ad spend is entered per landing page and per arbitrary period
The system SHALL let the owner record an ad spend amount for a specific landing page
(`mani`/`home`) and an arbitrary date range (`period_start`..`period_end`), rather than only one
blended figure per calendar month. Any report period's spend SHALL be computed by summing every
stored entry overlapping that period, prorated by calendar-day overlap for entries that only
partially overlap.

#### Scenario: Owner enters a real weekly figure
- **WHEN** the owner records $455.59 for `mani`, `period_start = 2026-07-13`,
  `period_end = 2026-07-19`
- **THEN** an Ads Report for that exact week on `mani` shows spend `$455.59` marked exact (not
  estimated)

#### Scenario: Report range only partially overlaps an entry
- **WHEN** the stored entries for `mani` are a single $738.53 entry for `2026-07-05`..`2026-07-19`,
  and the report requested is `2026-07-13`..`2026-07-19`
- **THEN** the reported spend is $738.53 prorated by the 7-day overlap out of the entry's 15 total
  days, and the period is marked estimated

#### Scenario: No entry exists for the requested page/period
- **WHEN** no `ad_spend_entries` row overlaps the requested page and range at all
- **THEN** the reported spend is `$0.00`, matching today's "absence means not entered yet" contract

### Requirement: Ads Report supports a month-to-date period
The system SHALL support a `month-to-date` report period producing exactly one row for
`[1st of the current month, today]`, distinct from a full calendar month — it SHALL NOT expand
past today into the rest of the month.

#### Scenario: Requesting month-to-date mid-month
- **WHEN** today is 2026-07-20 and the owner requests a month-to-date report for `mani`
- **THEN** the returned period is `2026-07-01`..`2026-07-20`, not `2026-07-01`..`2026-07-31`

### Requirement: Ads Report includes manager-follow-up bookings
The system SHALL include, in the same revenue and customer-count figures Ads Report already
computes for tracked-flow bookings, any real, non-cancelled Square appointment for the report's
page's ads-attributed contacts that `marketing.attribution` does not already know about (a lead a
manager booked by phone after the tracked flow didn't complete) — without double-counting a
booking already present in `marketing.attribution`.

#### Scenario: A lead's follow-up booking is found
- **WHEN** a contact under the requested landing page has no tracked booking, but Square shows a
  real, non-cancelled appointment for their linked customer ID not present in
  `marketing.attribution`
- **THEN** that appointment's value is included in `revenueCollected` or `anticipatedRevenue`
  (whichever applies by date) and the contact counts toward the period's follow-up count

#### Scenario: A tracked booking is never double-counted as a follow-up
- **WHEN** a contact's original tracked booking ID already exists in `marketing.attribution`
- **THEN** that same booking never appears in the follow-up count or figures, even if it is also
  the only booking Square currently shows for that customer

### Requirement: Ads Report offers table, WhatsApp-text, and chart views
The system SHALL render each report period in three interchangeable views: a table, a plain-text
block formatted for pasting directly into WhatsApp (with a one-click copy action), and — for the
Full Month period kind only — a trend chart across the page's month-over-month history.

#### Scenario: Copying the WhatsApp text view
- **WHEN** the owner selects the text view for a given period and clicks Copy
- **THEN** the clipboard contains the exact WhatsApp-formatted block (single-asterisk bold, no
  markdown tables) with no extra surrounding page chrome

#### Scenario: Chart view is unavailable outside Full Month
- **WHEN** the owner is viewing a Week, Month-to-date, or Custom period
- **THEN** the chart view option is not shown at all (not shown-but-disabled)

### Requirement: A Full Month report is viewable before the month ends
The system SHALL allow viewing the current, still-in-progress calendar month's report at any time,
labeled to indicate the month is not yet complete, rather than hiding it until month close.

#### Scenario: Viewing July's report on July 20th
- **WHEN** the owner requests the Full Month report for July while today is July 20th
- **THEN** the report renders with real data collected/anticipated so far, marked "in progress"

## REMOVED Requirements

### Requirement: Analytics tab as a standalone page
**Reason**: Its unique value (customer segments, completed-appointments list, upcoming-appointments
list) is superseded by a "View breakdown" drill-down on the Ads Report page for the currently
displayed period, which additionally includes manager-follow-up appointments the old Analytics tab
never showed.
**Migration**: `MarketingAnalyticsController`/`MarketingAnalyticsService.analytics()` are
unchanged and continue to back the new drill-down — no data or capability is lost, only the
top-level page and nav entry are removed.
