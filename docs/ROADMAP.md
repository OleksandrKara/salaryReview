# Salary Review — Roadmap

Living roadmap for the Square-sourced automated salary tool. Keep this updated as phases land.

## Done (Phase 1 — single-salon MVP)

- **Commission engine** — month-aware, no-clawback tiered true-up (45/55 base, 50/50 at 60+ counted
  services, paid as a month-close bonus). Manual owner/manager 50/50 grant override.
- **Square integration (read-only)** — bookings + orders + catalog + team members; order→provider
  attribution via customer+service+day join (validated 100% on real data), prepaid-invoice fallback.
- **Cash capture** — `cashew $nn` and Russian `наличные` appointment notes (amount, or catalog
  service total when omitted).
- **Rules** — gross/full-price commission basis (discounts absorbed); $60 tier cutoff (designs/add-ons
  excluded from the count); equal tip split on multi-provider tickets.
- **Persistence** — per-salon commission config, provider↔Square-member mapping with
  auto-provisioning, persisted tier grants (Flyway V4–V7).
- **Report UI** — `/reports` month view with tier badges, payouts, inline grant/revoke.
- **Auth (A)** — single shared owner login (see below).

## Next / deferred

### Phase 2 — Per-user accounts & roles  ← (B), explicitly deferred from Phase 1
Replace the single shared owner login with real accounts and **roles: owner / manager / provider**.
- **Owner** — super admin: config, all reports, grants, user management.
- **Manager** — CRUD on redos/adjustments, tag uncovered discounts, apply 50/50 grants.
- **Provider** — self sign-up; **read-only view of their own numbers**; approve or leave a correction
  comment on their settlement.
- Implies: user table, password hashing, role-gated endpoints (`@PreAuthorize`), provider↔account
  linking, an approval/comment workflow, and provider-scoped data access.
- The Phase-1 single login is intentionally a stopgap; this is the planned replacement.

### Phase 2 — Multi-tenant + Square OAuth
- Per-merchant Square OAuth (authorization-code flow, refresh tokens, encrypted at rest) replacing
  the single personal access token; tenant isolation (`merchant_id` on all rows); webhooks for sync.

### Phase 3 — Square App Marketplace + billing
- Marketplace listing (privacy policy, OAuth review, 5 active sellers), Stripe subscription billing.

### Smaller follow-ups
- **Provider-merge UI** — _not planned._ Pointing a second Square team-member ID at one provider
  (the "Brenda" case) was a one-off, handled manually; duplicates aren't expected going forward. The
  backend still supports it via `ProviderDirectory.linkTeamMember` if ever needed.
- **Retire the legacy manual path** — old `period_entries` / `PayPeriod` / `SettlementService` /
  `/api/pay-periods/**` and the old frontend pages (`/`, `/providers`, `/periods`) once `/reports`
  fully replaces them.
- **Manual adjustments UI** — redos/comps/hourly entry feeding the engine's `adjustments`.
- **Persist settlement snapshots** — freeze a month's computed numbers for history (Square data can
  change after the fact).
