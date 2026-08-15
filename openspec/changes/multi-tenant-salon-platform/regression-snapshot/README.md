# Golden regression snapshot — Business A, pre-migration baseline

Captured 2026-08-15, per `tasks.md` Phase 8.1 / `proposal.md`'s "How This Is Verified" section — the
reference every subsequent Phase 1+ migration must reproduce exactly before it's allowed to merge.

## What's here

- `settlements-2026-{02..07}.json` — full `GET /api/settlements/preview?year=2026&month=N` response for
  the last 6 fully closed months as of the capture date (August is still in progress, so July is the
  most recent closed month). One file per month, every provider, both halves, full precision.
- `owner-overview-2026-02_to_07.json` — `GET /api/owner/overview?fromYear=2026&fromMonth=2&toYear=2026&toMonth=7`
  for the same range (cross-month KPIs).

## How this was captured

**Not against live production directly.** Restored the most recent nightly backup
(`salonreview-20260814T033001Z.dump`) into a fully isolated, throwaway Postgres container (own name,
own port, own volume — never touching the live `salonreview-postgres` container or `~/salaryReview`),
ran this repo's current `master` (already includes both P0 payment-accounting fixes: custom-amount
line surfacing and cash-note gap-matching) against that copy with real Square production credentials
(read-only calls only), logged in as the real owner with a locally-reset password valid only in the
throwaway copy, pulled these two endpoints, then tore the whole isolated environment down. Live
production was never touched — same process used for every prior verification pass in this change
(see design.md/tasks.md for the pattern).

This is valid as a pre-migration baseline because the captured months are **closed** — Square data for
a month that's already ended does not change afterward, so a snapshot from a same-week backup is
equivalent to one taken the instant before the real migration runs. Do not extend this assumption to
the *current*, still-open month.

## What to compare after each Phase 1+ migration

Re-run the same two endpoints against the migrated schema (first against a fresh isolated copy while
developing, then against real production immediately after each migration ships) and diff:

- Every `providers[].firstHalf`/`secondHalf` field (`cardRevenue`, `cashCollected`, `tipsAfterFee`,
  `adjustments`, `tierBonus`, `cashTierRebate`, `zelleToProvider`, `cashToSalon`) — must match to the
  cent, for every provider, every half, every month. Any diff blocks merge.
- `monthZelleToProvider` / `monthCashToSalon` per provider.
- `diagnostics.*` counts (matched/unmatched line items, cash notes, orphan payments, cash-note gap
  matches/caps) — these should also be stable, since they depend only on Square data and the
  reconciliation logic, neither of which the tenant-scoping migrations touch.
- `owner-overview`'s `months[]` net-profit figures.

**Ignore `syncedAt`** on both endpoints — a live "last pulled from Square" timestamp, expected to
differ on every capture, not a regression signal.

## Quick summary (for a human sanity check, not the actual comparison basis — use the JSON)

| Month | Providers | Total Zelle | Total Cash-to-salon | Unattributed revenue |
|---|---|---|---|---|
| 2026-02 | 4 | $11,595.08 | $684.65 | $135.00 |
| 2026-03 | 5 | $10,989.69 | $1,110.55 | $570.00 |
| 2026-04 | 6 | $11,447.10 | $918.50 | $408.00 |
| 2026-05 | 7 | $12,358.53 | $1,667.35 | $825.11 |
| 2026-06 | 5 | $12,162.72 | $1,155.25 | $521.80 |
| 2026-07 | 4 | $13,203.04 | $1,331.65 | $85.01 |
