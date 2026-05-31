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
- **Per-user accounts & roles** — owner / manager / provider over a server-side session (see below).

## Next / deferred

### Done (Phase 2 — per-user accounts & roles)  ← (B), the deferred Phase-1 item
Replaced the single shared owner login with real accounts and **roles: owner / manager / provider**.
- **Owner** — super admin: all reports, tier grants, and **user management** (`/admin/users`).
- **Manager** — all reports + tier grants; no user management.
- **Provider** — **read-only view of their own month** (`/me`) + **approve / request-correction**
  (feedback shows as a badge on the owner/manager report).
- **Built:** `app_user` (V8) + `settlement_feedback` (V9); `AppUser`/`Role`/`SettlementFeedback`
  domain; `JpaUserDetailsService` + `AppUserPrincipal`; `SecurityConfig` rewritten to Spring Security
  **server sessions** (form login at `/api/login`, `@EnableMethodSecurity`, role-gated paths);
  `OwnerBootstrap` seeds the first owner from `APP_OWNER_*`; `UserController`,
  `SettlementSelfController` (`/api/settlements/me` + `/feedback`), feedback folded into the preview.
- **Frontend:** `middleware.ts` → **`proxy.ts`** (Next 16 rename) with role routing; login adopts the
  backend JSESSIONID into an httpOnly `sid` cookie + readable `role` cookie; proxies forward the
  session; new `/admin/users` and `/me` pages.
- **Accounts are owner-created** (no open self-signup — deferred, see below).

### Deferred from Phase 2
- **Open provider self-signup** — owner-invite only for now (avoids someone claiming another's payout).
- **Spring Session JDBC** — sessions are in-memory; a backend restart logs everyone out. Persist to
  Postgres when that becomes a problem.
- **Role-gated salon-config writes** — config is still seeded/managed directly; gate when a config UI lands.

### Phase 2 — Multi-tenant + Square OAuth
- Per-merchant Square OAuth (authorization-code flow, refresh tokens, encrypted at rest) replacing
  the single personal access token; tenant isolation (`merchant_id` on all rows); webhooks for sync.

### Phase 3 — Square App Marketplace + billing
- Marketplace listing (privacy policy, OAuth review, 5 active sellers), Stripe subscription billing.

### Prepaid invoices (reviewed packages) — DONE
A customer pays one Square **invoice** in advance for several services, then draws them down over
later visits. The old auto-heuristic was removed (it mislabelled late checkouts and could pay on
fabricated appointments); off-day order lines go to the owner/manager **Unattributed sales** review
list. Built (**Option C**): checkout policy + reviewed prepaid packages.
- **Policy:** check out prepaid visits in Square when possible, so they attribute automatically via
  the ±2-day match.
- **Prepaid packages** (`prepaid_package` V11, `prepaid_redemption` V12; `PrepaidService` +
  `PrepaidController` at `/api/prepaid`, owner+manager): owner/manager records a package (customer +
  Square customer id, provider, paid date, amount, # services — manual entry). The review screen
  (`/admin/prepaid`) lists **real Square bookings** for that customer+provider since the paid date
  (not cancelled, not already redeemed, not already checked out within ±2 days) as candidates to
  **confirm**; each confirmed draw-down decrements the balance; at 0 it shows **"No credit left —
  needs payment."** Confirmed redemptions pay the provider on the **service date** at the catalog
  menu price (channel `PREPAID`), folded into `SettlementPreviewService` so they flow into the
  payout, `#salary` Card/procedures, and the breakdowns. Anti-fraud: draw-downs only ever confirm
  against real bookings; balance caps over-redemption; unique (booking, service) stops double-redeem.
- **Deferred:** Square Invoices API auto-import (manual entry now); cross-provider packages; editing a
  package's totals (delete + recreate).

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
