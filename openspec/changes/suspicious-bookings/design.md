## Context

`SquareMonthAggregator` already does all the heavy lifting needed for detection. As it iterates each month it maintains:

- `paidBookings` — set of `bookingId`s that had at least one order line matched to them (any channel: card, cash-checkout, prepaid).
- `cashCheckedOutBookings` — subset of `paidBookings` where the matched order tendered cash.
- `cashEntries` (CashBooking records) — bookings where a cash note (`cashew $nn` / `cash` / `наличные`) was parsed from the seller or customer note.
- `ownerCustomerIds` — Square customer IDs flagged as the owner / family.

A "suspicious" booking is the negation of all the above: it happened (status + past), nothing matched an order, no cash note, not an owner customer. The existing aggregator doesn't expose these sets externally — they're all locals inside `aggregate()`. The minimal refactor: add a new field on `MonthAggregation` (e.g., `List<SuspiciousCandidate>`) populated in the same pass. No second Square query, no duplicated logic.

The owner needs a way to mark "I looked at this and it's fine" so each suspicious booking only nags once. That's the clearance table.

## Goals / Non-Goals

**Goals:**
- Surface every appointment that happened with no money trail, per provider per half-month, with a one-click Clear / Undo workflow.
- Reuse the existing aggregator pass — no extra Square API calls and no parallel detection logic.
- Show a small, scannable badge on `/reports` so the owner notices without having to open a separate menu.
- Honor existing role gating (OWNER + MANAGER); providers see nothing.

**Non-Goals:**
- No notification system (email, SMS, push).
- No bulk clear in V1 — one click per booking.
- No detection across providers (e.g., "this customer's missing payment is suspicious across the whole salon") — strictly per-booking.
- No catalog-price fallback when the catalog lookup fails — we show `—` for price rather than misreporting.
- No write to Square.

## Decisions

### D1: Extend `MonthAggregation` to expose suspicious candidates

**Decision:** Add a `List<SuspiciousCandidate>` field to `SquareMonthAggregator.MonthAggregation`. The candidate record carries `bookingId`, `customerId`, `customerName` (best-effort), `providerId`, `providerName`, `serviceVariationId`, `serviceName`, `day` (LocalDate), `startAt` (Instant), `half` (FIRST/SECOND), `gross` (BigDecimal — catalog price for the service variation). Populated inline in `aggregate()` alongside the other passes.

**Rationale:** Single source of truth for booking iteration. We already have all the data; surfacing it costs nothing.

**Alternatives considered:**
- *Separate detector service*: would re-pull and re-classify bookings. Same logic in two places = drift.
- *Expose `paidBookings` and `cashEntries` raw*: forces every caller to redo the join. Wrap it once.

### D2: Detection runs live, not stored

**Decision:** Detection happens on every `GET /api/suspicious` (per request) and inside every `GET /api/settlements/preview` (the existing one). No daily cron, no snapshot table. The clearance table is the only persisted state.

**Rationale:** Bookings can be updated after-the-fact (rescheduled, cancelled, an order can land late). Stale detection would be worse than no detection. The aggregator is already called for every load.

### D3: Clearance keyed by `square_booking_id`, not a composite

**Decision:** The clearance table uses `square_booking_id VARCHAR(255) UNIQUE` as the natural key. A booking is "cleared" or "not cleared" — no per-half or per-month nuance.

**Rationale:** A booking has exactly one start time, so it belongs to exactly one half-month. Once cleared it's cleared everywhere. Simpler queries, simpler UI.

### D4: Per-half counts on `ProviderPayout` DTO

**Decision:** `ProviderPayout` (already returned by `GET /api/settlements/preview`) gains two integer fields: `firstHalfSuspicious` and `secondHalfSuspicious`. The `SettlementPreviewService` computes them by:
1. Pulling the aggregator's suspicious-candidate list,
2. Looking up which booking IDs are cleared (one DB hit, `findAllBySquareBookingIdIn`),
3. Filtering and grouping by provider × half.

**Rationale:** `/reports` already calls `GET /api/settlements/preview`. Two extra ints in the response keep the badge zero-cost on render.

### D5: Detail endpoint returns "cleared" rows too

**Decision:** `GET /api/suspicious?...` returns **all** suspicious bookings for the provider × half — uncleared first, then cleared, with a flag and the clearer's name/timestamp on each row. The badge count only includes uncleared.

**Rationale:** The owner who already cleared something wants to see it again to undo if they made a mistake. Hiding cleared rows entirely makes Undo undiscoverable.

### D6: Owner-customer exclusion is **mandatory** in V1

**Decision:** Bookings whose `customerId` is in the `owner_customer` table are excluded from suspicious detection. There is no override.

**Rationale:** Owner comps are by-design no-order bookings — flagging them as suspicious is noise that would train the owner to ignore the list.

### D7: Catalog price lookup is best-effort

**Decision:** The detector uses the same `catalogPrices(varIds)` map the aggregator already builds. If a variation ID has no price, the suspicious row shows `gross = null` and the UI renders `—`.

**Rationale:** A missing price doesn't disqualify the row from being suspicious — the appointment still happened, we just can't put a dollar value on it. The owner can still review.

### D8: Half assignment uses the salon timezone

**Decision:** A booking is assigned to FIRST (1-15) or SECOND (16-end) using its `startAt` converted to the salon's local timezone (same logic as the existing `halfOf` in `SquareMonthAggregator`).

**Rationale:** Consistency with the rest of the commission engine.

### D9: Customer name resolution is bulk and lazy

**Decision:** After the aggregator returns suspicious candidates, we issue one bulk `square.customerNames(...)` call to resolve names — the same pattern already used for unmatched lines. If lookup fails, we render the Square customer ID.

**Rationale:** No new API endpoint for naming. Best-effort, never blocks the response.

### D10: Security gating

**Decision:** Add `/api/suspicious/**` to the existing `OWNER, MANAGER` matcher in `SecurityConfig`, alongside `/api/settlements/**`, `/api/redos/**`, etc.

**Rationale:** Same role boundary as related admin features. Method-level checks aren't needed.

### D11: No Flyway data migration for existing data

**Decision:** The `suspicious_booking_clearance` table is empty at launch. Every past booking that meets the suspicious criteria today appears uncleared.

**Rationale:** Honest backlog. The owner reviews what they reviewed.

### D12: Clearance record retention and indexes

**Decision:** The migration adds an index on `cleared_at DESC` for the (future) "cleared history" tab. No expiration / pruning policy — clearance rows are cheap.

## Risks / Trade-offs

- **Noise on first load.** When the owner first sees the badge it may show a backlog of past suspicious bookings going back to whenever Square was connected. We can't avoid this — we surface the unknowns honestly. The Clear workflow is the path to a clean slate.
  - Mitigation: copy on the page makes clear that "old, untracked" appointments are expected here and Clear is the right tool for them.

- **Aggregator coupling.** Adding the suspicious-candidate list to `MonthAggregation` increases its surface. New callers of the aggregator will get this for free; we should be sure that's fine.
  - Mitigation: the field is `List<SuspiciousCandidate>` — small, plain data. Anyone who doesn't care just ignores it.

- **Race between Clear and Square update.** If a missing order lands AFTER the owner clears the booking, the booking will stay cleared (correct) but the clearance reason now stale. Low impact — clearance is a "I looked at this" stamp, not a payment record.

- **`square_booking_id` length.** Square booking IDs are short (typically <50 chars), but the column is sized `VARCHAR(255)` to match the convention used by `no_show_fee_override`.

- **The badge gets long if N is large.** "⚠ 99+ review" is the cap. Beyond that we just show 99+.

- **Performance: extra DB hit on `/reports`.** The clearance lookup adds one `findAllBySquareBookingIdIn` per pulse. Cheap (an IN query over a small table). Verified by `EXPLAIN ANALYZE` mentally — acceptable.

- **Provider-side leak.** Providers must not see this anywhere. The `/api/suspicious/**` rule + `me.role` check on the page is the gate; we'll keep the badge off `/me` and the detail page redirects providers to `/me`.
