## ADDED Requirements

### Requirement: Detect suspicious bookings from the existing month aggregation
The system SHALL classify a booking as "suspicious" when **all** of the following conditions are met:
1. The booking's `startAt` is strictly before the current instant (the appointment is in the past).
2. The booking's status is one of `ACCEPTED`, `PENDING`, or any status other than `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_SELLER`, `DECLINED`, `NO_SHOW`.
3. No Square order line was matched to the booking by the existing `SquareMonthAggregator` matching logic.
4. Neither the booking's `sellerNote` nor its `customerNote` is recognized by `CashNoteParser`.
5. The booking's `customerId` is NOT in the `owner_customer` table.

The detection SHALL run inside the existing aggregation pass — no additional Square API call SHALL be made for suspicious detection.

#### Scenario: Past appointment, no order, no note → suspicious
- **WHEN** a booking has `status = ACCEPTED`, `startAt` is in the past, no matching order line, no cash note, and the customer is not an owner-customer
- **THEN** it appears in the suspicious-bookings list for its provider and half-month

#### Scenario: Cancelled appointment → not suspicious
- **WHEN** a booking has `status = CANCELLED_BY_CUSTOMER`
- **THEN** it does NOT appear in the suspicious-bookings list regardless of other fields

#### Scenario: Cash-noted appointment → not suspicious
- **WHEN** a booking's `customerNote` contains `"cashew $80"`
- **THEN** it does NOT appear in the suspicious-bookings list

#### Scenario: Owner-customer appointment → not suspicious
- **WHEN** a booking's customer is recorded in `owner_customer`
- **THEN** it does NOT appear in the suspicious-bookings list

#### Scenario: Future appointment → not suspicious
- **WHEN** a booking's `startAt` is in the future
- **THEN** it does NOT appear in the suspicious-bookings list

### Requirement: Persist Clear/Undo state per booking
The system SHALL store one row per cleared booking in a `suspicious_booking_clearance` table keyed by `square_booking_id` UNIQUE. Each row SHALL record `cleared_by_username`, `cleared_at` (ISO timestamp), and an optional `note`. Undoing a clearance SHALL delete the row.

#### Scenario: Mark booking cleared
- **WHEN** an owner posts `POST /api/suspicious/{bookingId}/clear` with an optional `note`
- **THEN** a `suspicious_booking_clearance` row exists with that `square_booking_id`, the username of the authenticated owner, the current timestamp, and the note

#### Scenario: Idempotent clear
- **WHEN** an owner posts `POST /api/suspicious/{bookingId}/clear` for a booking that is already cleared
- **THEN** the response is 200 OK and the existing clearance row is unchanged

#### Scenario: Undo clearance
- **WHEN** an owner posts `DELETE /api/suspicious/{bookingId}/clear`
- **THEN** the `suspicious_booking_clearance` row for that booking is removed and the booking re-appears in the uncleared list

### Requirement: Per-provider per-half count surfaced on the settlement preview
The system SHALL augment each `ProviderPayout` returned by `GET /api/settlements/preview` with `firstHalfSuspicious` and `secondHalfSuspicious` integer fields. Each count SHALL include only UNcleared suspicious bookings for that provider in that half-month.

#### Scenario: Mixed cleared and uncleared
- **WHEN** a provider has 2 suspicious bookings in FIRST half, one of which has been cleared
- **THEN** the provider's `firstHalfSuspicious` value is 1

#### Scenario: No suspicious bookings
- **WHEN** a provider has no suspicious bookings in SECOND half
- **THEN** the provider's `secondHalfSuspicious` value is 0

### Requirement: Detail endpoint returns both cleared and uncleared rows
The system SHALL expose `GET /api/suspicious?year=&month=&half=FIRST|SECOND&providerId=` returning every suspicious booking for that provider × half, sorted by `startAt` ascending. Each row SHALL include: `bookingId`, `date`, `time` (salon-local, e.g. `2:30 PM`), `customerId`, `customerName` (best-effort), `serviceName`, `gross` (catalog price, nullable), `cleared` (boolean), `clearedBy` (username, null if uncleared), `clearedAt` (instant, null if uncleared), `clearedNote` (null if uncleared or no note).

#### Scenario: Detail list includes a cleared booking
- **WHEN** an owner requests the detail list for a provider × half and one booking was previously cleared
- **THEN** that booking appears in the response with `cleared: true` and the clearer's username and timestamp populated

### Requirement: Clickable badge on the /reports provider row
The system SHALL show a small clickable badge next to each provider's period total on the `/reports` page when that provider has at least one uncleared suspicious booking in that half. The badge SHALL display the count and link to `/reports/{providerId}/suspicious?year=&month=&half=FIRST|SECOND`. When the count is 0 the badge is not rendered.

#### Scenario: Badge appears
- **WHEN** an owner loads `/reports?year=2026&month=6` and a provider has `secondHalfSuspicious = 3`
- **THEN** the badge "⚠ 3" (or similar) is shown in the 16-end cell for that provider, linking to the detail page

#### Scenario: Badge hidden when zero
- **WHEN** a provider has `firstHalfSuspicious = 0` and `secondHalfSuspicious = 0`
- **THEN** no badge is rendered for that provider

### Requirement: Detail page enables Clear and Undo
The system SHALL render `/reports/{providerId}/suspicious` for OWNER and MANAGER roles only, showing the list of suspicious bookings for the requested provider × half. Each row SHALL have a Clear button (or Undo button if already cleared) that calls the corresponding endpoint and refreshes the list.

#### Scenario: Non-owner blocked
- **WHEN** a PROVIDER navigates to `/reports/{providerId}/suspicious`
- **THEN** they are redirected to `/me`

#### Scenario: Clearing reduces the badge count
- **WHEN** an owner clicks Clear on a row
- **THEN** the row visually moves from "Uncleared" to "Cleared" and on the next load of `/reports` the badge count is one lower

### Requirement: Suspicious endpoints are owner-and-manager only
The system SHALL gate `/api/suspicious/**` to `hasAnyRole('OWNER', 'MANAGER')` in `SecurityConfig`.

#### Scenario: Provider request blocked
- **WHEN** a PROVIDER calls any `/api/suspicious/*` endpoint
- **THEN** the response is 403 Forbidden
