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
