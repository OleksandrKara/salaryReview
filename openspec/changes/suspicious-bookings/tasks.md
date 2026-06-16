## 1. Backend — Migration and domain

- [x] 1.1 Create Flyway migration `V20__suspicious_booking_clearance.sql` with table `suspicious_booking_clearance` (id BIGSERIAL PK, square_booking_id VARCHAR(255) UNIQUE NOT NULL, cleared_by_username VARCHAR(100) NOT NULL, cleared_at TIMESTAMPTZ NOT NULL DEFAULT now(), note VARCHAR(255) NULLABLE) + index on `cleared_at DESC`
- [x] 1.2 Create `SuspiciousBookingClearance` JPA entity in `com.salonreview.domain`
- [x] 1.3 Create `SuspiciousBookingClearanceRepository extends JpaRepository<SuspiciousBookingClearance, Long>` with finders: `findBySquareBookingId(String)`, `findAllBySquareBookingIdIn(Collection<String>)`, `deleteBySquareBookingId(String)`

## 2. Backend — Aggregator surface

- [x] 2.1 Add `SuspiciousCandidate` public record inside `SquareMonthAggregator` with: `bookingId`, `customerId`, `providerId`, `providerName`, `serviceVariationId`, `day`, `startAt`, `half`, `gross` (nullable). Service name resolved in the service layer.
- [x] 2.2 Populate a `List<SuspiciousCandidate>` in a second pass over `bookings` after the order-matching loop completes (so `paidBookings` is fully populated). Filter: past + in-status + not paid + no cash note + not owner-customer.
- [x] 2.3 `MonthAggregation` record gains `suspicious()` field; updated all existing test constructors (7 files).

## 3. Backend — Service and DTOs

- [x] 3.1 Create `SuspiciousBookingDto` record with: `bookingId`, `date` (yyyy-MM-dd), `time` (h:mm a salon-local), `customerId`, `customerName` (nullable), `serviceName` (nullable), `gross` (nullable BigDecimal), `half` ("FIRST"/"SECOND"), `cleared` (bool), `clearedBy` (nullable), `clearedAt` (nullable Instant), `clearedNote` (nullable)
- [x] 3.2 Create `SuspiciousBookingService` with `list()` (detail) + `summaryFor()` (badge counts, dedupes multi-segment bookings, returns Map<providerId, int[FIRST, SECOND]>)
- [x] 3.3 Add `clear(bookingId, username, note)` and `unclear(bookingId)` — both transactional, clear is idempotent

## 4. Backend — Wire into SettlementPreview

- [x] 4.1 Inject `SuspiciousBookingService` into `SettlementPreviewService`
- [x] 4.2 In the previewFor flow, call `summaryFor(year, month)` once and pipe per-provider counts through `toPayout`
- [x] 4.3 Add `firstHalfSuspicious` and `secondHalfSuspicious` int fields to `ProviderPayout` (record-level, not on `HalfSettlement` — the counts are payment-state, not settlement-engine state)

## 5. Backend — Controller and security

- [x] 5.1 Create `SuspiciousBookingController` with: `GET /api/suspicious` (params: year, month, half, providerId — returns `List<SuspiciousBookingDto>`); `POST /api/suspicious/{bookingId}/clear` (body: optional `{note}`, returns 200; uses authenticated principal for `cleared_by_username`); `DELETE /api/suspicious/{bookingId}/clear` (returns 204)
- [x] 5.2 In `SecurityConfig`, add `/api/suspicious/**` to the existing `hasAnyRole("OWNER", "MANAGER")` matcher group

## 6. Backend — Tests

- [x] 6.1 Write `SuspiciousBookingDetectionTest` — 7 scenarios cover each exclusion rule (clean past booking → flagged; cancelled/no-show → excluded; cash note in seller or customer note → excluded; owner customer → excluded; future → excluded)
- [x] 6.2 Write `SuspiciousBookingServiceTest` for clearance round-trip: clear inserts; idempotent clear is no-op; unclear removes

## 7. Frontend — Types and proxy

- [x] 7.1 Add `SuspiciousBooking` type to `frontend/app/lib/types.ts` mirroring the DTO
- [x] 7.2 Add `firstHalfSuspicious: number` and `secondHalfSuspicious: number` to `ProviderPayout`
- [x] 7.3 Created `frontend/app/api/suspicious/route.ts` (GET) and `frontend/app/api/suspicious/[bookingId]/clear/route.ts` (POST + DELETE)
- [x] 7.4 Added `listSuspicious(year, month, half, providerId)` to `serverApi.ts`

## 8. Frontend — Reports badge

- [x] 8.1 Created `SuspiciousBadge` component + plumbed `badge` prop through `MoneyCell` and `MobileMoney`; rendered on both 1–15 and 16–end cells (desktop table + mobile cards) wired to `firstHalfSuspicious` / `secondHalfSuspicious`
- [x] 8.2 Badge hidden when count = 0; cap at 99+; tooltip "N appointment(s) need(s) review"; data-testid for E2E

## 9. Frontend — Detail page

- [x] 9.1 Created `frontend/app/reports/[providerId]/suspicious/page.tsx` (server component, redirects PROVIDER role to `/me`)
- [x] 9.2 Header + period label + ← Report link; responsive list (cards/rows) with date/time, customer, service, gross
- [x] 9.3 `SuspiciousList` client component holds state, calls proxy routes, optimistic UI on Clear/Undo with error rollback
- [x] 9.4 Cleared rows in a visually-muted "Cleared earlier" section showing clearer's username + timestamp + Undo button

## 10. Verification

- [x] 10.1 `./mvnw test` — 65/66 unit tests pass (only `contextLoads` requires DB; passes in CI per AGENTS.md). New tests: 7 detection + 3 service round-trip.
- [x] 10.2 `npx tsc --noEmit` — clean
- [x] 10.3 Docker rebuilt; V20 migration applied on startup (`Successfully applied 1 migration … now at version v20`); backend healthy
- [x] 10.4 Ready for manual smoke at `localhost:3000/reports` logged in as `olexandr.kara2` — badge appears on cells where uncleared suspicious bookings exist; click → detail page; Clear/Undo round-trip works
