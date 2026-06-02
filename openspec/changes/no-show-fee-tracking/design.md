## Context

No-show bookings are currently discarded: `SquareMonthAggregator.didNotHappen()` drops `NO_SHOW` / `CANCELLED_*` / `DECLINED` before attribution, so a no-show is invisible and providers are never paid the $25 fee a manager may have collected. A read-only probe of real Square data (Jan–Jun 2026, see `proposal.md`) found: 26 no-shows; 0 auto-charges; 4 fees collected, **all** as a manager-sent Square invoice whose paid form is a **COMPLETED order with a "Cancelation Policy" line ≈ $25** for that customer, on or just after the no-show date.

Constraints: Square is **strictly read-only**; the DB is never the source of truth for Square facts — existing features (tier grants, feedback, redos, manual credits, owner comps) persist only *human decisions* and re-derive everything else from Square per request. This design follows that pattern.

## Goals / Non-Goals

**Goals:**
- Make every no-show visible per provider (owner/manager on `/reports`, the provider on `/me`).
- Auto-detect a paid $25 cancellation fee from Square and credit the provider $25 with no manual entry in the common case.
- Keep the $25 out of the 50/50 tier; land it in the period the fee was paid.
- Persist only manager overrides; derive everything else live from Square.

**Non-Goals:**
- No Square writes; no card auto-charging; no unpaid-fee chasing; no fee-rule engine beyond a per-record default of $25. (See `proposal.md` Non-goals.)

## Decisions

### D1 — Detect on the COMPLETED "Cancelation Policy" order, not the invoice object
The paid fee surfaces as a COMPLETED order carrying a line item named ~`Cancelation Policy` (Square's spelling, single "l") of ≈ $25, with `customer_id` set and a `closed_at`. We already pull completed orders for settlement, and orders carry the customer, the line-item names, and the paid timestamp in one object — whereas the `Invoice` record only exposes a total (line items live on the invoice's order). So detection keys on the order/line, which is also more robust to fees charged as a sale rather than an invoice (the "it varies" reality). *Alternative — invoice-based (status PAID + total≈$25):* needs an extra order fetch for the line name and misses non-invoice charges. *Alternative — auto-charge/payment scan:* rejected; 0 occurrences in real data.

### D2 — Pair each fee to the nearest preceding no-show within 2 months
The customer may pay the fee the same day or later, capped at a **2-month** gap (owner decision). We build pairings over a rolling window: list all `NO_SHOW` bookings (customer + providers from `appointmentSegments[].teamMemberId` via `ProviderDirectory` + `start_at`) and all cancellation-fee charges (COMPLETED order, line name `/cancel\w*\s*policy/i`, amount = $25, with `customer_id` + `closed_at`). For each fee charge, pair it to the **nearest preceding `NO_SHOW` for the same customer within the prior 2 months**; a fee is **consumed once** (one fee never pays two no-shows). The 2-month cap keeps the bookings/orders queries cheap and bounds how long a no-show stays "open" before it's just left as "no fee collected." *Alternative — amount-only match:* too loose ($25 services exist); label-primary with amount as a guard is safer. *Alternative — unbounded forward window:* heavier queries and indefinitely-open no-shows; 2 months covers the realistic pay-late gap, and the `CONFIRM` override backfills any rare payment beyond it.

### D3 — Persist only overrides (V18 = `no_show_fee_override`)
No-shows and detected fees are computed live each request. The only persisted state is the manager's exception, keyed by `square_booking_id`:
- `CONFIRM` — credit the provider $25 even though no fee order was detected (cash / quick-sale / odd label), with a manager-entered `fee_paid_date` and `amount`.
- `SUPPRESS` — do not credit despite detection (false positive / disputed).

Columns: `id, square_booking_id (unique), kind, provider_id, amount NUMERIC(10,2) DEFAULT 25, fee_paid_date DATE NULL, note VARCHAR NULL, created_by, created_at`. Mirrors `redo` / `manual_credit`. *Alternative — persist every no-show row:* redundant with Square, drifts, more migration surface.

### D4 — Fold into settlement as a signed `NOSHOW` line via `applyExtraLines`
A new `NoShowFeeService.noShowFeeLinesByProvider(year, month)` returns, per provider/half, the credited fees for that period: `detected (minus SUPPRESS) ∪ CONFIRM`. Each becomes an `AttributedService` on a new channel **`NOSHOW`**: `gross = net = +amount`, `discount = 0`, `tip = 0`, `counted = 0`, `countedUnits = 0` (so it never affects the tier), `customer` = the no-show customer, `date` = fee paid date. **Detected fees credit automatically — no manager click.** If a no-show booking spans **multiple providers** across its segments, the $25 is **split evenly** among the distinct providers (e.g. two providers → $12.50 each), one `NOSHOW` line per provider. `SettlementPreviewService` gains the service in its constructor (like `redoRepo`, `manualCredits`) and threads its lines through `applyExtraLines`, `salaryMessage` (a one-line "No-show fee: +$25 (customer, date)" note, like redo/manual), `toPayout`, and `providerDetail`.

### D5 — Month membership = fee-payment month (else the no-show's own month)
A no-show belongs to exactly one displayed month so the row and its credit always travel together:
- **Fee paid** → the no-show shows in the month the fee was paid (`closed_at` month for detected, `fee_paid_date` month for CONFIRM), and the +$25 credit applies in that same month.
- **No fee** → the no-show shows in its own calendar month, no credit.

Consequence: a previously-unpaid no-show that gets paid in a later month **relocates** from its own month to the payment month on the next render — intended, since everything re-derives live from Square and the credit must land in the paid period (consistent with redo deductions landing in the action period, not the already-paid original period).

### D6 — Listing a month, endpoints & visibility
From the broad pairing (D2), the rows shown for month **M** are: **(A)** every fee paired and **paid in M** — shown with its (possibly earlier) no-show and the +$25 credit; **∪ (B)** every `NO_SHOW` in M that is **unpaired across the whole window** — shown as "no fee collected." Set A is exactly what produces the `NOSHOW` credit lines for M.
- `GET /api/no-show-fees?year&month` (OWNER+MANAGER, identical capabilities) → those rows: booking, provider, customer, no-show local time, fee (amount/paid-date) | none, effective state (credited / suppressed / confirmed) — enough to render the table and offer override controls.
- `POST /api/no-show-fees/confirm` and `DELETE /api/no-show-fees/{bookingId}` (and a suppress action) for overrides; `SecurityConfig`: `/api/no-show-fees/**` = `hasAnyRole("OWNER","MANAGER")`.
- Provider visibility: extend the existing `/api/settlements/me` detail with the same month-scoped `noShows` rows (date, customer, fee status) so the provider sees their own no-shows; the UI renders them as a **breakdown at the bottom of `/me`**, styled like the existing services and discounts breakdowns. The `NOSHOW` credit lines also flow into the provider's line breakdown.

## Risks / Trade-offs

- **Label drift** ("Cancelation Policy" renamed) → detection silently misses → *Mitigation:* amount≈$25 guard, every no-show is shown so a missing credit is visible, and the `CONFIRM` override backfills it.
- **Wrong $25 order matched** (a real $25 service for that customer near the date) → false credit → *Mitigation:* label-primary match + consume-once + `SUPPRESS` override.
- **Booking lacks `customer_id`** → can't match → shows "no fee collected"; manager `CONFIRM`s.
- **Date window tuning** → too tight misses next-day payments, too wide over-claims → start at [−1d, +21d], nearest-wins; revisit with data.
- **Multi-provider / multi-segment no-show** (rare) → the $25 is split evenly across the distinct providers on the booking, one credit line each.

## Migration Plan

V18 `no_show_fee_override` is additive (forward-only Flyway). Deploy via normal CI → push to master. Rollback is safe: the table holds only overrides, and all no-show/fee facts re-derive from Square, so dropping the table and removing the `NOSHOW` channel restores prior behavior with no data loss.

## Resolved decisions (owner)

- **Capabilities:** owners and managers have identical access (`hasAnyRole("OWNER","MANAGER")`).
- **Month membership:** a paid no-show lives in its fee-payment month (per D5).
- **Pairing cap:** 2 months max between no-show and payment (per D2); beyond that, `CONFIRM` override backfills.
- **Fee amount:** exactly $25.
- **No manager click:** detected fees credit automatically (override only for off-signal/false-positive cases).
- **Multi-provider no-show:** split the $25 evenly across the distinct providers.
- **Provider UI:** a no-show breakdown at the bottom of `/me`, styled like the services/discounts breakdowns.

## Open Questions

- None outstanding.
