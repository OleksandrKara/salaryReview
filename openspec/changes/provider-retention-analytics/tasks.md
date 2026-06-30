## 1. Data capture prerequisites

- [x] 1.1 Map Square's `created_at` onto `SquareClient.Booking` (currently unmapped); confirm it's returned by the bookings search and parse it in the salon timezone.
- [x] 1.2 Confirm `AttributedService` exposes everything ingest needs (customerId, providerId, date, channel/category); note how null `customerId` (anonymous) services appear.

## 2. Visit ledger

- [x] 2.1 Migration: `provider_visit` (id, customer_id, provider_id, service_date, rebooked_same_day, category, created_at) with a UNIQUE on `(customer_id, provider_id, service_date)` and indexes on `(provider_id, service_date)` and `(customer_id, service_date)`. Decide how to represent anonymous visits (null customer_id allowed but excluded from rates).
- [x] 2.2 Entity + repository.

## 3. Ingest (accrual + backfill)

- [x] 3.1 Visit-ingest service: for a given date, aggregate the month, take services with `date == target`, upsert one visit per `(customer, provider, date)`; set `rebooked_same_day` from that day's bookings (`created_at` on the visit date, future start). Idempotent.
- [x] 3.2 Backfill: loop past months (target 12–24, bounded by Square availability), reusing 3.1. Idempotent against accrual.
- [x] 3.3 Wire daily accrual into the existing revenue-snapshot scheduler/startup hooks; run the backfill once (guarded so it doesn't re-run every boot).

## 4. Analytics service (query-time classification)

- [x] 4.1 New/returning per provider per month via window functions (`MIN(service_date)` per `(customer,provider)` and per `customer`); new-to-salon-via-P.
- [x] 4.2 Cohort retention (provider + salon) within K (default 60d, configurable); compute maturity (`now ≥ cohort_ref + K`) and never report immature cohorts as low/zero.
- [x] 4.3 Same-day rebook rate per provider per month.
- [x] 4.4 Trend series (month-over-month clientele size + new/returning mix); acquisition-leak flag (high new-to-salon-via-P + low matured retention vs threshold).
- [x] 4.5 Anonymous (null customer) visits reported separately, excluded from rates.

## 5. API (owner-only)

- [x] 5.1 Owner-only controller under `/api/owner/**` (already OWNER-gated): provider summary table for a month + per-provider trend/cohort detail. DTOs.

## 6. Frontend (owner-only, mobile + web)

- [x] 6.1 Page under `/owner/...`: provider table (clients seen, new/returning, new-to-salon-via-P, retention %, rebook %, leak flag) with month navigation.
- [x] 6.2 Per-provider detail: trend chart + cohort retention with clear "matured vs too-soon" treatment.
- [x] 6.3 Responsive (cards on mobile, table on web); types + proxy routes + nav entry (owner menu).

## 7. Tests & verification

- [x] 7.1 Ingest idempotency (re-run a date) and backfill-vs-accrual equivalence on a seeded fixture.
- [x] 7.2 New/returning, cohort retention (incl. maturity gating), and rebook computed correctly on hand-built fixtures.
- [x] 7.3 Owner-only gating (manager/provider denied) — transitive per repo convention.
- [ ] 7.4 Frontend `tsc`/`eslint`/`next build`; manual sanity-check new/returning + rebook against Square's Appointments → Performance report for one month.
