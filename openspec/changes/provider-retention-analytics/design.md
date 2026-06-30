## Context

The app computes monthly figures on demand via `SquareMonthAggregator`, which already emits `AttributedService(customerId, providerId, date, channel, …)` — the atomic "customer X saw provider Y on day D" fact. Nothing customer-level is persisted; `revenue_snapshot` (daily capture) is the precedent for "Square doesn't expose historical state, so we collect it ourselves." `Booking` carries `startAt`/`updatedAt` but **not** `created_at` (Square returns it; we don't map it). This change is additive analytics — no payroll/aggregator behavior changes.

## Goals / Non-Goals

**Goals**
- Per-provider, per-month: new-vs-returning clients, retention, same-day rebook, and trend — computed from a persistent ledger so longitudinal questions are answerable and fast.
- Surface the **acquisition-leak risk** (new salon clients via P who don't return).
- Honest metrics: cohort censoring handled; trade-offs visible (scorecard, not one opaque number).

**Non-Goals**
- Inferring assignment *intent* ("who gave P the client") — unmeasurable from Square; we measure first-time pairings.
- A single composite score as the headline (a transparent composite is a later phase).
- Per-category (Nails/PMU) retention windows in v1; payroll changes; manager access.

## Decisions

**D1 — Persistent visit ledger (Path B), not on-demand deep queries (Path A).** Retention is longitudinal; computing it on-demand means paginating years of bookings/orders across all customers every view (slow, rate-limit risk, and "new-ever" is unprovable without *all* history). A ledger makes trend a `GROUP BY month` and cohorts natural, and it's cheap to populate — the aggregator already yields the tuples. Consistent with `revenue_snapshot`. *Path A rejected for performance + the same reason snapshots exist.*

**D2 — Thin fact table; classify at query time, not at write time.** Store **raw visits** — `(customer_id, provider_id, service_date, rebooked_same_day, category)` — and derive *new/returning/first-visit/cohort* with SQL window functions (`MIN(service_date)` per customer, per `(customer,provider)`). Baking `new_to_provider`/`new_to_salon` flags at ingest is fragile: a later backfill of *older* data would silently misclassify already-written rows. Query-time classification self-corrects as the ledger deepens. *Denormalized flags rejected for that correctness trap.*

**D3 — Visit granularity = one row per `(customer, provider, service_date)`.** Dedupe multiple services the same day into one "visit" (the retention unit is "a day a client saw a provider"). Multiple providers same day → multiple rows (the client visited each). Idempotent upsert on the natural key.

**D4 — Track BOTH "new-to-salon" and "new-to-provider," and BOTH provider- and salon-retention.** They answer different questions (see the taxonomy below); the ledger computes all four cheaply. The owner's "we give P new clients and they vanish" is specifically **new-to-salon-via-P** with low return — a salon risk attributed to P, distinct from "P's regulars don't rebook." Show both; the scorecard leans on provider-retention + rebook.

**D5 — Retention window K, with cohort maturity.** Default **K = 60 days** (≈ one nail rebook cycle), configurable. A cohort (clients whose first visit with P is in month M) is **matured** only when `now ≥ cohort_reference + K`; until then it's **in-flight** and shown as "too soon," never as 0%/bad. *Censoring is a hard requirement, not cosmetic — without it recent months look alarming and the report loses trust.* PMU's longer cycle is noted as a later per-category refinement (Open Questions).

**D6 — Same-day rebook from `Booking.created_at`.** Map Square's `created_at`. Rebook = the customer has a *future-dated* booking whose `created_at` falls on the **same calendar day** (salon timezone) as the visit. Computed at ingest into `rebooked_same_day`. *Approximates Square's "rebooked before leaving"; same-calendar-day is the simple, defensible v1.*

**D7 — Ingest reuses the aggregator + the snapshot scheduler pattern.** A daily job aggregates the current month, upserts that day's visits (services with `date == target`), and sets `rebooked_same_day` from that day's bookings. A one-time **backfill** loops past months (target 12–24, bounded by what Square returns) doing the same. Idempotent, so re-runs and overlap with daily accrual are safe.

**D8 — Owner-only.** Customer-count/PII-heavy + provider performance evaluation → under the owner-gated surface (`/owner/**` API, `/owner/...` page). Managers/providers are out (consistent with the recent manager-scope change).

**D9 — Scorecard first, composite later.** Headline = a small table of honest metrics (retention %, rebook %, new-client count/growth, leak flag). An *optional* composite score, if added, is a transparent weighted blend with documented/tunable weights — never a single opaque number that hides the regular-keeper-vs-new-grower trade-off.

## The metric taxonomy (reference)

```
relative to SALON                          relative to PROVIDER
─────────────────                          ────────────────────
new-to-salon: first visit ANYWHERE         new-to-provider: first visit with P
new-to-salon-via-P: first salon visit      returning-to-P: visited P, seen P before
   was with P  ("fresh client P got")
salon-retention: returned to the salon     provider-retention: returned to P
acquisition-leak risk = many new-to-salon-via-P  +  low return
```

## Risks / Trade-offs

- **Cohort censoring** (D5) — mitigated by the maturity gate + clear UI labeling.
- **Intra-salon client movement** — clients drift between providers; a regular switching A→B is "new-to-B" and an apparent "loss" for A, which may be preference, not failure. Provider-retention is noisier than salon-retention; show both and label them.
- **Backfill depth bounds "new-ever"** — a customer first seen before the backfill window looks "new" on their first in-window visit. Mitigation: backfill as deep as Square allows (target 24mo); show the ledger's start date so the owner reads early months with that caveat.
- **`created_at` availability/timezone** — if Square omits/garbles it for some bookings, `rebooked_same_day` is best-effort (treated false). Document it.
- **Customer identity gaps** — visits with a null `customerId` (cash/walk-in with no Square customer) can't be attributed to retention; count them separately as "anonymous," don't let them distort rates.
- **PII** — owner-only; consider whether customer-level drill-down is needed at all vs. counts only.

## Migration Plan

1. Map `Booking.created_at`.
2. `provider_visit` table + entity/repo (indexes on `(provider_id, service_date)`, `(customer_id, service_date)`).
3. Ingest service (daily upsert + bounded backfill) on the aggregator; wire into the existing snapshot scheduler/startup.
4. Analytics service (window-function queries: new/returning, cohorts with maturity, rebook, trend) + owner-only controller.
5. Owner analytics page (provider table + per-provider trend/cohort visuals), mobile + web.
6. Run backfill once in prod; verify against Square's Performance report for a sanity check on new/returning + rebook.

**Rollback:** drop the page/endpoints and the table; the ledger is additive and untouched by the rest of the app. Booking `created_at` mapping is harmless to keep.

## Open Questions

- **K and provider- vs salon-retention emphasis** — confirm K=60d and whether the scorecard headlines provider- or salon-retention.
- **Per-category windows** — do Nails and PMU need different K (PMU cycle is months)? (Later refinement.)
- **Backfill depth** — 12 or 24 months (data availability + Square rate limits)?
- **Composite score** — build it in v1 (transparent weights) or stay scorecard-only?
- **Customer-level drill-down** — show *which* clients churned (actionable but more PII), or counts/rates only?
- **Anonymous (no-customer-id) visits** — how prominently to surface them.
