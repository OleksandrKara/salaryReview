## Why

salaryReview is architected for exactly one salon. Every one of its 54 JPA entities and 83 Flyway
migrations assumes a single Square merchant, a single set of users, and a single set of business
rules — there is no `business_id`/`tenant_id` anywhere in the schema, `salon_config` (the one table
that already holds commission rates as data instead of code) is a hard singleton
(`CHECK (id = 1)`, read via `findById(1)` in 13 service classes), and the single `SquareClient` bean
is constructed once at boot from one `SQUARE_ACCESS_TOKEN`/`SQUARE_LOCATION_ID` pair.

We are onboarding a second, unrelated salon — **Anna Kara Brow Studio ("AK PMU")**, its own Square
account, Pacific timezone, roughly two providers — with a flat 45%/55% commission split (no tiering,
no no-show fee; confirmed with the owner, see design.md D10). Standing it up today would mean cloning
the whole repo to a second VPS with a second `.env` and a second Postgres — full infrastructure
duplication, and every future salon would repeat that cost. This change makes the existing
single-tenant application multi-tenant in place: one codebase, one deployment, N businesses, each with
its own Square connection, users, providers, customers, financial config, and data — with the first
salon (Business A) continuing to behave exactly as it does today after migration.

Full analysis, alternatives considered, and rationale for every decision below live in
[`design.md`](./design.md). A companion standalone document with the complete file-by-file findings
this proposal is grounded in was produced alongside this change — see
[`../../../MULTI_TENANT_ARCHITECTURE_ANALYSIS.md`](../../../MULTI_TENANT_ARCHITECTURE_ANALYSIS.md) at
the repo root — that document is the audit trail (including a consolidated list of corrections applied
to this proposal/design/tasks after independent verification against the code); this proposal and
design are the actionable spec.

## What Changes

- **New `business` table** — the tenant root. Every business-owned table gains a `business_id`
  (direct column, or inherited via an already-tenant-scoped FK — see design.md's full table
  classification).
- **`salon_config` stops being a singleton** — drop `CHECK (id = 1)`, add `business_id`, backfill one
  row per business. This alone unblocks per-salon commission rates, tier thresholds, and cash-fee
  parameters with **zero change to `TierCommissionEngine`'s math** (design.md §Financial Rules
  Architecture — the engine is already correctly parameterized; it just reads a singleton today).
- **`SquareClient` becomes per-business.** Credentials move from process env vars to a new
  `square_connection` table (one encrypted-at-rest personal access token + location id per business,
  matching today's manual-token model — Square OAuth itself stays a later phase per the existing
  ROADMAP.md). A `SquareClientProvider` resolves/caches a `SquareClient` instance per business on
  demand; `SquareClient`'s internals (cache, throttle semaphore, TTLs) are untouched and become
  correctly tenant-scoped for free because each business gets its own instance.
- **`app_user` gains `business_id`**; username uniqueness becomes `(business_id, username)`. Role
  model (OWNER/MANAGER/PROVIDER/ADS_MANAGER) is unchanged — it already means "role within the
  business you're bootstrapped into." A `GLOBAL_ADMIN` capability is added narrowly (platform-owner
  only, no new day-to-day workflow) rather than a full second role tier.
- **Every scheduled job that touches Square or sends SMS/Telegram** (`ProviderVisitScheduler`,
  `RevenueSnapshotScheduler`, the 6 SMS automation schedulers) iterates connected businesses instead
  of assuming one; ShedLock keys gain a `businessId` suffix so businesses don't serialize behind each
  other.
- **Frontend gains a `businessId` session cookie** alongside the existing `sid`/`role` cookies; a
  business-context row is added to the existing `AdminMenu.tsx` dropdown, rendered as **plain text
  with no switcher affordance** for any user who belongs to exactly one business (i.e., unchanged UX
  for Business A's existing users on day one).
- **Global-singleton config tables** (`twilio_sms_config`, `telegram_notification_config`,
  `marketing_sync_status`, `sms_automation`, `blocked_number`, `rag_agent_config`) each gain
  `business_id` and move off their `getSingleton()` / fixed-PK pattern.

## Non-Goals (this change)

- **Square OAuth (authorization-code flow, refresh tokens).** Stays the documented Phase-2-after-this
  work per `docs/ROADMAP.md`. This change ships per-business *personal access tokens*, encrypted at
  rest — mechanically identical to today's setup, just multiplied by business instead of by process.
- **Billing/subscriptions/Stripe.** Not needed for two known salons; the domain model is shaped so it
  doesn't get harder to add later (see design.md §SaaS Readiness), but nothing is built now.
- **Public self-signup / a marketing signup funnel.** Both salons are and will remain owner-invited,
  matching today's "no open provider self-signup" decision.
- **Multi-business-per-user membership / business switching for providers.** Every real user today
  belongs to exactly one salon and that remains true after this change; the schema and session model
  support a user belonging to one business at a time (an owner *could* be a member of two businesses
  in the schema, but the switcher UI and workflow for that are out of scope until a real need
  exists).
- **Retrofitting every non-core feature (RAG, SOPs, marketing funnel/ads analytics, KB) with full
  per-business content in this change.** These get `business_id` columns (so they're safe and
  correct once a second business exists) and stay feature-flagged off by default for Business B,
  rather than being actively built out for it. See design.md §Feature Configuration.
- **Rewriting `TierCommissionEngine`'s formula shape**, unless Business B's actual cash-calculation
  difference turns out to require it (open question — see design.md; the formula is already fully
  parameterized and near-certainly only needs new config values, not new code).
- **Migrating the legacy manual-entry path** (`SettlementService`/`period_entries`/`/api/pay-periods`)
  — already marked for retirement in ROADMAP.md; left untouched, not tenant-scoped, not used by
  Business B.

## How This Is Verified

- **Regression, not just new tests.** Before any schema change, capture a snapshot of Business A's
  last 6 closed months' settlement numbers (`/api/settlements/preview` output per provider/month) and
  the owner-overview net-profit figures. After migration, re-run the same requests against the
  migrated schema (Business A now carrying its backfilled `business_id`) and assert byte-for-byte
  equal `BigDecimal` output. This is the single most important test in the whole change — see
  design.md §Migration and §Testing.
- New backend integration tests stand up **two businesses** in the same test database (Business A
  fixture + a synthetic Business B) and assert: Business A's `/api/settlements/preview`,
  `/api/reports`, `/api/users`, `/api/suspicious`, and RAG-search endpoints never return a row that
  belongs to Business B, and vice versa, under real repository queries (not mocked) — this is the
  cross-tenant isolation test suite.
- `./mvnw test` (existing 23+ test classes) must stay green throughout — TierCommissionEngine's unit
  tests in particular pin the formula and must not need behavior changes, only fixture wiring
  (a `businessId`/`SalonConfig` no longer fetched via literal `1`).
- Manual localhost check per repo convention: `docker compose up -d --build`, log in as the existing
  Business A owner (`olexandr.kara2`), confirm `/reports`, `/me`, `/owner/overview` render identical
  numbers to before the change.
