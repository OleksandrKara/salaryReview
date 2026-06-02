## Why

When a customer no-shows, a manager sometimes charges a $25 fee — but providers have no visibility into whether the fee was ever sent or paid, and they are never compensated for it. Today no-show bookings are deliberately dropped from the report entirely, so the lost slot is invisible to everyone.

A read-only probe of real Square data (Jan–Jun 2026) settled the open question: Square is **not** auto-charging no-shows (0 of 26). Every collected fee was a **manager-sent Square invoice with a "Cancelation Policy" line ≈ $25, status PAID** — and only 4 of 26 no-shows had a fee at all. That invoice is a reliable, machine-readable signal we can detect and act on without any Square writes.

## What Changes

- Surface no-shows that today are filtered out: a per-provider **no-show table** sourced from Square `NO_SHOW` bookings (provider, customer, date/time), visible to owners/managers on `/reports` and to each provider on `/me`.
- For each no-show, **auto-detect the paid fee** by matching a PAID Square invoice with a "Cancelation Policy" line ≈ $25 for the same customer near the no-show date. Tag each no-show **fee paid ✓ / no fee collected**.
- When a fee is detected paid, **compensate the provider $25** via a new synthetic settlement line (channel `NOSHOW`), folded through the existing `SettlementPreviewService.applyExtraLines`. The $25 is a fee reimbursement and does **not** count toward the 50/50 tier.
- A no-show belongs to the **month its fee was paid** (same day or up to 2 months later), where its +$25 credit also lands; a no-show with no fee shows in its own month as "no fee collected." On `/me` this renders as a no-show breakdown at the bottom of the page, like the services and discounts breakdowns. A multi-provider no-show splits the $25 evenly. Detected fees credit automatically (no manager click).
- Manager **override**: confirm/credit a no-show whose fee was collected off-signal (cash, quick-sale, non-standard invoice), and un-do an auto-detected credit. The detected case needs no manual entry.

## Capabilities

### New Capabilities
- `no-show-fee-tracking`: detect no-show bookings and their paid $25 cancellation-fee invoices from Square (read-only), show them per provider, and compensate the provider $25 with a manager override.

### Modified Capabilities
<!-- None. No existing OpenSpec specs yet; settlement behavior is extended additively via a new synthetic line channel, not by changing an existing documented requirement. -->

## Impact

- **Backend:** new Flyway migration **V18** (`no_show_fee` record); new domain/repo; a detection service (reuses `SquareClient.bookings`, `invoicesForCustomer`, the `didNotHappen`/no-show status set, and `ProviderDirectory`); fold-in via `SettlementPreviewService.applyExtraLines` with a new `NOSHOW` channel; new endpoints under `/api/no-show-fees/**` gated OWNER+MANAGER in `SecurityConfig`; one provider-visible read on `/api/settlements/me/**`.
- **Frontend:** a no-show table component on `/reports` (owner/manager, with override controls) and a read-only no-show section on `/me`; proxy routes under `app/api/*` + `app/lib/api.ts` helpers.
- **Square:** read-only only — Bookings + Invoices Search; no new Square write scopes.
- **Verification:** backend unit test that a paid "Cancelation Policy" invoice matched to a no-show adds a +$25 `NOSHOW` line that does not increment counted/tier; localhost end-to-end against the 4 known real fee-paid no-shows (Tatiana 2026-05-02, Susan 2026-04-18, etc.); Square access stays read-only.

## Non-goals

- **No Square writes** — we never create or send the $25 invoice; managers continue to do that in Square. This feature only reads and reflects it.
- No auto-charging of cards on file (Square isn't doing it; out of scope to build).
- No chasing/automation of *unpaid* fees — unpaid/never-sent fees simply show as "no fee collected."
- No configurable fee schedule beyond a default $25 (stored per record so exceptions are editable, but no rules engine).
- No back-pay reconciliation of historical no-shows beyond what the month view naturally recomputes.
