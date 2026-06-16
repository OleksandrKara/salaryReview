## Why

When a service is performed but Square shows no checkout AND the provider didn't leave a cash note, the salon has no record of who paid for it — the appointment happened, but the money trail is missing. Today these slip through silently: the commission engine just doesn't pay on them, the owner never sees a list, and there's no signal that "Jane did 3 services on Tuesday that nobody paid for." This change surfaces those appointments per provider, per half-month, with a Clear/Undo workflow so once an owner reviews each one (cash given to the front desk later, comp not yet recorded, owner forgot to add a note, …) it stays off the radar.

## What Changes

- New backend detector that, for the selected month, finds bookings meeting **all** of:
  - `startAt` is in the past (salon-local).
  - status is **not** `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_SELLER`, `DECLINED`, or `NO_SHOW`.
  - No order line matched the booking (the existing `paidBookings` set inside `SquareMonthAggregator`).
  - No cash note in `sellerNote` or `customerNote` (the existing `CashNoteParser` returns empty).
  - Customer is **not** in the owner-customer list (those are legit no-order comps).
- Each booking is also checked against the new `suspicious_booking_clearance` table — if the owner/manager has already cleared it, it's filtered from the suspicious list (still counted on a separate "cleared" tab for transparency).
- New Flyway migration `V20__suspicious_booking_clearance.sql` (table: `square_booking_id` UNIQUE, `cleared_by_username`, `cleared_at`, optional `note`).
- New `SuspiciousBookingService` that hooks into the existing `SquareMonthAggregator` output (extending the aggregator to surface the booking↔attribution map cleanly) and joins with the clearance table.
- The existing `SettlementPreview` per-provider DTO gains `firstHalfSuspicious` and `secondHalfSuspicious` counts so the badge on the `/reports` provider list renders without a second network round-trip.
- New endpoints:
  - `GET /api/suspicious?year=&month=&half=FIRST|SECOND&providerId=` — detail list for a provider×half.
  - `POST /api/suspicious/{bookingId}/clear` — mark cleared (body: optional note).
  - `DELETE /api/suspicious/{bookingId}/clear` — undo.
  - Owner+Manager-gated (same as `/api/settlements/**`).
- New page `/reports/[providerId]/suspicious?year=&month=&half=` listing each suspicious booking with date, time, customer, service, catalog price, and Clear/Undo buttons.
- On `/reports`, a small inline badge (e.g. `⚠ 3 review`) appears in the period cell when there are uncleared suspicious bookings for that provider×half — clickable, links to the new page.
- The badge and detail page are gated to OWNER + MANAGER (providers don't see anything).

## Non-goals

- No new Square integration; reuses existing `SquareMonthAggregator` data.
- Not retroactively detecting suspicious bookings for past months that the owner never opened — the detection runs live each time `/reports` is loaded, against fresh Square data.
- No automated alerts (no email/SMS/push). The badge in the UI is the surface.
- No bulk clear (one click per booking). Bulk can come later if the volume justifies it.
- No edit of cleared rows other than Undo — the clearance table records *who* cleared *when*; if the wrong person cleared, Undo + re-Clear by the right person is the path.
- No detection that includes **prepaid** or **manual-credit** explanations as exclusions in V1 — those are rare exceptions; if they cause noise, we add them as filters in a later iteration. (Owner-comp is excluded because it's the common case.)
- Not a replacement for the existing **Unmatched Lines** trace in the per-provider detail view — that surfaces the inverse (order lines with no booking). Both lists can exist; they answer different questions.

## Capabilities

### New Capabilities

- `suspicious-bookings`: Detection of past, in-status bookings with no payment trail (no order, no cash note) plus a per-booking Clear/Undo workflow surfaced as a per-provider per-half badge on the `/reports` page.

### Modified Capabilities

*(none — `/reports` and `SettlementPreview` get additive fields, but no existing requirement changes)*

## Impact

- **Backend**: New `SuspiciousBookingClearance` entity + repository. New `SuspiciousBookingService` (detection + clearance). New `SuspiciousBookingController`. `SettlementPreviewService` populates two new int fields per provider × half. `ProviderPayout` DTO + frontend type get `firstHalfSuspicious`, `secondHalfSuspicious`. Detection requires `SquareMonthAggregator` to expose the `paidBookings` set (or wrap into a method) — a small refactor.
- **DB**: Flyway V20 (`suspicious_booking_clearance` table).
- **Security**: `/api/suspicious/**` → `hasAnyRole('OWNER', 'MANAGER')` in `SecurityConfig`.
- **Frontend**: New page `app/reports/[providerId]/suspicious/page.tsx`; new proxy route under `app/api/suspicious/*`; new types; new `SuspiciousBookingsList` client component with Clear/Undo; badge on the provider row in the existing `/reports` page.
- **Dependencies**: None new.
- **Verification**: Backend unit test of detection logic (each exclusion rule tested) + clearance round-trip test (Clear then Undo). Manual check at `localhost:3000/reports` logged in as `olexandr.kara2` — confirm badge appears, click through, Clear / Undo work, badge count updates.
