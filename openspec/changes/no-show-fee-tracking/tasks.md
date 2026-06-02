## 1. Backend — schema & domain

- [ ] 1.1 Add Flyway `V18__no_show_fee_override.sql`: table `no_show_fee_override(id, square_booking_id UNIQUE NOT NULL, kind VARCHAR NOT NULL CHECK (kind IN ('CONFIRM','SUPPRESS')), provider_id BIGINT NOT NULL REFERENCES providers(id), amount NUMERIC(10,2) NOT NULL DEFAULT 25, fee_paid_date DATE NULL, note VARCHAR NULL, created_by VARCHAR, created_at TIMESTAMPTZ DEFAULT now())`.
- [ ] 1.2 `domain/NoShowFeeOverride.java` (Lombok, mirror `Redo`/`ManualCredit`) and `repo/NoShowFeeOverrideRepository` (`findBySquareBookingId`, `findAll`, `deleteBySquareBookingId`).

## 2. Backend — detection service

- [ ] 2.1 Add `SquareClient` support for reading order line-item names if not already exposed (confirm `Order.lineItems[].name` + `totalMoney` + `customerId` + `closedAt` are mapped; extend the record minimally if needed).
- [ ] 2.2 `square/NoShowFeeService`: over a 2-month rolling window, build pairings — list all `NO_SHOW` bookings (reuse `square.bookings`, the no-show status set, `ProviderDirectory`; salon-local time via location timezone) and all cancellation-fee charges (COMPLETED, customer match, line name `/cancel\w*\s*policy/i`, amount = $25, `closed_at`); pair each fee to the **nearest preceding** no-show for that customer **within the prior 2 months**, consume-once. Per D1/D2.
- [ ] 2.3 Month membership (D5): a paired no-show belongs to its fee-payment month; an unpaired no-show to its own month. For a requested month M, rows = (A) fees paid in M with their (possibly earlier) no-show + credit, ∪ (B) no-shows in M unpaired across the window. Apply overrides: `detected − SUPPRESS ∪ CONFIRM`. Detected fees credit with no manager click. Expose `noShowFeeLinesByProvider(year, month)` returning signed `NOSHOW` `AttributedService` lines (gross=net=+amount, tip=0, counted=0, countedUnits=0, date=fee paid date); when a no-show spans multiple providers, **split** the $25 evenly into one line per distinct provider.
- [ ] 2.4 Expose the month's no-show listing (booking, provider, customer, local no-show time, fee amount/paid-date or none, effective state) for the admin table and for the provider's own (own-rows-only) view.

## 3. Backend — settlement integration

- [ ] 3.1 Add `NOSHOW` to the `AttributedService` channel set.
- [ ] 3.2 Inject `NoShowFeeService` into `SettlementPreviewService` (constructor, like `redoRepo`/`manualCredits`) and thread its lines through `applyExtraLines`, `toPayout`, and `providerDetail`.
- [ ] 3.3 Add a concise `#salary` note in `salaryMessage(...)`: "No-show fee: +$25 (customer, date)" — one line, do not overcomplicate.
- [ ] 3.4 Extend the `/api/settlements/me` detail payload with a `noShows` array (date, customer, fee status) for provider visibility.
- [ ] 3.5 Update the test constructors of `SettlementPreviewService` to pass the new dependency (mock), matching prior redo/manual-credit additions.

## 4. Backend — endpoints & security

- [ ] 4.1 `web/NoShowFeeController` (`/api/no-show-fees`): `GET ?year&month` (admin list); `POST /confirm` (CONFIRM override: bookingId, providerId, amount?, feePaidDate?, note?); `DELETE /{bookingId}` and a suppress action for overrides.
- [ ] 4.2 `SecurityConfig`: gate `/api/no-show-fees/**` with `hasAnyRole("OWNER","MANAGER")`.

## 5. Frontend

- [ ] 5.1 Proxy routes under `app/api/no-show-fees/*` (mirror `app/api/manual-credits`) + `api.ts` helpers (`listNoShowFees`, `confirmNoShowFee`, `clearNoShowFee`).
- [ ] 5.2 `/reports`: a no-show table (owner + manager, identical) for the displayed month — per provider, each no-show with fee status badge (paid ✓ / no fee) and override controls (confirm / un-do / suppress). Add a nav link via `AdminMenu`.
- [ ] 5.3 `/me`: a `NoShowBreakdown` component at the **very bottom** of the page (mirroring `DiscountBreakdown` / `ServiceBreakdown`) listing the provider's no-shows for that month with fee status, read-only; add the `NOSHOW` channel tag/color in `types.ts` + the lines table so the credit reads clearly.

## 6. Tests & verification

- [ ] 6.1 Backend unit test: a matched paid "Cancelation Policy" order adds a +$25 `NOSHOW` line for the provider in the paid period, and leaves counted-services/tier unchanged.
- [ ] 6.2 Backend unit test: a fee paid in a later month moves the no-show + credit to the payment month; a multi-provider no-show splits $25 evenly; `SUPPRESS` removes a detected credit; `CONFIRM` adds a credit with no detection; override endpoints reject non-OWNER/MANAGER.
- [ ] 6.3 `npx tsc --noEmit` + frontend build clean.
- [ ] 6.4 Localhost end-to-end (read-only Square): verify the 4 known fee-paid no-shows (e.g. Tatiana 2026-05-02, Susan 2026-04-18) show "fee paid ✓" and credit $25; a fee-less no-show shows "no fee collected"; confirm no Square writes occur.
