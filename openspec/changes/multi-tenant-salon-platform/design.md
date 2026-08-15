## Context

salaryReview is a Spring Boot 4 / Java 21 + Next.js 16 payroll-and-ops platform for one nail salon
(AK.LUX.NAILS), backed by Postgres 16 with 83 Flyway migrations (`V1`–`V83`; **next migration is
`V84`**, not `V18` as `openspec/config.yaml`'s stale context claims). It has grown well past "a
commission calculator": 54 JPA entities span commission/settlements, Square sync, SMS marketing
automation (Twilio, 6 automations), a RAG knowledge assistant (pgvector), SOPs/KB content, bank
statement reconciliation, staff documents, and Telegram notifications shared with two sibling Next.js
apps (`mani`/salonLandings, `akluxnails-home`) via an internal API key.

There is currently **zero tenant concept anywhere** — no `business_id`/`tenant_id` column exists in
any table, no repository query filters by anything tenant-like, and the single `SquareClient` Spring
bean is constructed once at boot from one personal access token. This design adds a `business_id`
tenant boundary across the schema and application layer while leaving Business A's (the existing
salon's) behavior, numbers, and UX unchanged.

## Goals / Non-Goals

See `proposal.md` for the authoritative Non-Goals list. Goals, in priority order:

1. Business A continues to work identically — same URLs, same login, same numbers, same jobs.
2. Business B can be onboarded with its own Square connection, users, providers, and commission
   config, without duplicating infrastructure.
3. Cross-tenant data leakage is structurally prevented (defense in depth: schema constraint +
   repository-layer filter + integration test), not merely "not currently exercised."
4. A third, fourth, fifth business is an onboarding operation (INSERT a `business` row, connect
   Square, invite users), not a code change.
5. Minimum necessary change — do not rebuild subsystems (SMS automation platform, RAG, commission
   math) that are already well-designed; add a tenant key to them.

## Decisions

### D1 — Tenant terminology: `Business`, not `Salon`/`Organization`/`Tenant`

**Decision:** the primary domain noun is **`Business`** (table `business`, column `business_id`
everywhere, Java type `Business`).

**Why:** "Salon" is accurate today but wrong as a platform noun — nothing prevents future
non-salon-but-similar service businesses, and baking "salon" into the schema/API vocabulary
(`SalonConfig` already exists and is being *repurposed*, not renamed, to avoid unnecessary churn — see
D6) would force a rename later. "Tenant" is the correct *architectural* word but reads as generic
SaaS-platform jargon in code that owners/managers never see, and the codebase's existing user-facing
language is already business-oriented (`salon_config.owner_short_name`, `internal.api.key` comments
referring to "the salon"). "Organization" implies a multi-location/multi-brand umbrella that doesn't
exist here — every business in scope (today and foreseeably) is one salon with one Square merchant.
**"Business" is used consistently as the noun in code and API; "tenant" is used only in prose/design
docs to describe the isolation *pattern*, never as a class or column name** — avoids exactly the
"Business/Organization/Tenant mixed randomly" failure mode the task called out.

### D2 — One business per Square merchant; one Square location per business (for now)

Confirmed from the Square-integration research: `SquareClient` structurally threads a single
`locationId` through every call, and there is no code path handling multiple locations. Both known
businesses (A and B) are single-location. `square_connection` is modeled 1:1 with `business` (not
1:N) for this change; if a future business needs multiple physical locations, that becomes a
`square_location` child table under `square_connection` — deliberately not built now (no evidence of
need, avoids speculative generality).

### D3 — `BusinessMembership` join table, not a direct `app_user.business_id` column

**Decision:** add `business_membership(id, business_id, user_id, role, created_at)` rather than a
bare `app_user.business_id` column, **but** keep the *workflow* single-business-per-user (a user has
exactly one active membership row in practice today; the schema doesn't forbid a second).

**Why:** the auth-fork research found no existing case of a user needing >1 business, so a direct
column would be simpler. But `app_user` already carries login credentials (`password_hash`) that are
naturally per-*person*, not per-*business-relationship* — and the one plausible near-term case (the
platform owner, i.e. you, wanting to see both Business A and Business B without two separate logins)
is exactly a multi-membership case. The join table costs one extra table and one extra query today
and avoids a much more disruptive migration (splitting login identity from business role) the moment
a second membership is needed. `AppUserPrincipal` gets an `activeBusinessId` resolved at login (the
one membership row for 99% of users; a picker only if `>1` row exists) — **zero UX change** for
single-membership users, satisfying the "simple for the single-business user" requirement directly.

### D4 — Role model: keep the 4 existing roles per-business; add narrow `GLOBAL_ADMIN`, not a 5th
business-facing tier

**Decision:** `OWNER`, `MANAGER`, `PROVIDER`, `ADS_MANAGER` (unchanged, already exactly right for
"manage one business" through "operational view of one business") become **roles scoped to a
business membership row**, not global. A separate, minimal `platform_admin` flag (not a `Role` enum
value — a boolean on a small `platform_admin` table keyed by `user_id`, checked only by a handful of
new platform-level endpoints: `/api/platform/businesses`, `/api/platform/businesses/{id}/suspend`)
covers "you, managing both salons' onboarding." **Rejected:** adding `GLOBAL_ADMIN` as a fifth
`Role` enum value — every existing `hasRole(...)`/`hasAnyRole(...)` matcher in `SecurityConfig` would
need to be re-audited for whether a global admin should implicitly pass it, which is exactly the kind
of blanket-privilege bug multi-tenant systems get bitten by. A separate, additive check keeps existing
business-scoped authorization untouched and makes platform-admin power explicit and narrow.

**Resolved (was Open Question 3): the platform owner ("Super Admin," owner's own term) explicitly
must access every business from one account, with no separate per-business login.** This confirms
`platform_admin` above is the right shape (one flag, one account, works across businesses by
construction — it's not scoped to a `business_membership` row at all) and additionally means the
`BusinessMembership` multi-row switcher (D3, D12/Phase 6.2) **needs a working UI in the first
release**, not a schema-only deferral — the owner will have `>1` membership row (or, more precisely,
platform-admin status plus at least one direct membership) from day one of AK PMU's onboarding, so the
"single membership → no switcher UI" fast path doesn't cover this real user starting immediately.

### D5 — Square credentials: DB-backed personal access token per business now; OAuth is a later,
separate change

Confirmed: `SquareProperties`'s own doc comment already states *"Phase 1 uses a single personal
access token... Phase 2 replaces this with per-merchant OAuth tokens."* This change **is not** that
Phase 2. It ships the mechanically necessary step — moving the existing token model from
"one token in `.env`" to "one token per business in a DB table, encrypted at rest, pasted in by the
owner during onboarding exactly as it is today" — and explicitly leaves the OAuth authorization-code
flow, refresh-token handling, and Square App Marketplace review as later, separately-proposed work.
Building OAuth now, before it's needed for two known salons, would be exactly the
over-engineering the task instructions warn against.

New table `square_connection`: `id, business_id (FK, unique), environment, access_token_encrypted,
location_id, merchant_id (nullable — Square doesn't require it be known upfront), connected_by_user_id,
connected_at, last_sync_at`. Encryption: Spring's existing dependency surface has no crypto library in
use today; add a small AES-GCM wrapper (env-var master key, matching the `docker-swarm-secrets`
proposal's file-based-secret delivery model for that master key specifically — the *master* key is
deployment-wide infra and belongs in swarm secrets; the *tokens* it wraps are per-business and belong
in the DB, per that proposal's own scoping note).

**Correction from verification against the code:** the integration is not strictly read-only —
`SquareClient.addCustomerToGroup`/`removeCustomerFromGroup` are real mutating calls (used by the
same-day-rebooking SMS automation); no financial object is ever written. And the set of classes
directly injecting `SquareClient` (the Phase 3.5 call-site migration) is **~35 files, not ~10** —
see `MULTI_TENANT_ARCHITECTURE_ANALYSIS.md` §8.2 for the full list. Re-budget Phase 3 effort
accordingly.

### D6 — `salon_config` is repurposed, not replaced

`salon_config` already proves the right shape for per-business financial rules (`V4`'s own migration
comment: *"replacing the values that were previously hardcoded"*). The fix is mechanical: drop
`CHECK (id = 1)`, change PK to a normal surrogate or reuse `business_id` as the PK (1:1 with
business — simplest), backfill Business A's existing row with its `business_id`. The 13 call sites
(`MarketingContactsService.java:681`, `SettlementService.java:50`, `ProviderDirectory.java:37`,
`MarketingAnalyticsService.java:1290`, `PrepaidService.java:225`, `CancelledAppointmentService.java:247`,
`RevenuePulseService.java:214`, `OwnerOverviewService.java:112`, `SettlementPreviewService.java:275,388`,
`RevenueSnapshotService.java:260`, `ProviderVisitIngestService.java:158`,
`SuspiciousBookingService.java:368`) change from `salonConfig.findById(1)` to
`salonConfig.findByBusinessId(currentBusinessId())` where `currentBusinessId()` comes from a new
request-scoped `CurrentBusinessContext` (see D8). **No change to `TierCommissionEngine`, `CommissionConfig`,
or any commission math** — this table already fully parameterizes `baseRate`, `tierRate`,
`tierServiceThreshold`, `servicePriceCutoff`, `cardTipFeeRate`. Renaming `SalonConfig` → `BusinessConfig`
is deliberately deferred (pure rename, zero behavior change, do in a follow-up cleanup PR, not this
migration-bearing change) to keep this diff reviewable.

### D7 — `CurrentBusinessContext`: request-scoped resolver, not a parameter threaded everywhere

**Decision:** a single new Spring-managed request-scoped bean, populated once per request in a
security filter (right after Spring Security resolves `AppUserPrincipal`, which gains an
`activeBusinessId` field per D3), exposing `Long id()` and `Business business()`. Repositories and
services call `currentBusinessContext.id()` instead of taking a `businessId` parameter through every
method signature.

**Why:** the alternative — adding a `businessId` parameter to every one of the ~150 repository/service
methods across 65 entities — is the literal `if (salon == A)` anti-pattern the task explicitly says
to avoid, just parameterized instead of branched, and it would touch far more files than necessary for
equivalent safety. A context resolved once from the authenticated session and read via a
request-scoped bean is the standard, low-blast-radius pattern for this in Spring, and mirrors how
`SecurityContextHolder` already works in this codebase (`AppUserPrincipal` is already pulled from
context via `@AuthenticationPrincipal` in ~15+ controllers, e.g. `SuspiciousBookingController.java:46`
— corrected citation; `SuspiciousTriageController` does not exist as a class). Background
jobs (D9) get an explicit, code-visible equivalent since there's no HTTP request to derive it from.

### D8 — Tenant filter enforcement: Hibernate filter as defense-in-depth, explicit repository
methods as the primary mechanism

**Decision:** every tenant-scoped repository method is rewritten to take/derive `businessId`
explicitly (e.g. `AppUserRepository.findByBusinessIdAndUsername(businessId, username)` replacing
`findByUsername(username)`), backed by a composite unique index. **In addition**, apply Hibernate's
`@FilterDef`/`@Filter` (`org.hibernate.annotations.Filter`) on every tenant-scoped entity, enabled
per-session from `CurrentBusinessContext`, as a second, independent enforcement layer — so a
repository method that's *missed* in the rewrite (inevitable across 60 repository files) still can't
return cross-tenant rows. This directly answers the task's "never rely on frontend filtering for
tenant security" and "every important architectural decision must be justified" requirements: two
independent layers (explicit query + session filter) fail closed, not open, if either is forgotten on
a given code path. Rejected: relying on the Hibernate filter *alone* — it's easy to disable
accidentally (native queries, `@Query(nativeQuery=true)` bypass it entirely, and several existing
repositories use native SQL, e.g. `TrafficSourceSql`, `MerchantRuleEngine`'s pg_trgm queries) — so
explicit `business_id` predicates remain mandatory in those paths.

### D9 — Background jobs iterate businesses explicitly; `RevenueSnapshotScheduler`'s existing pattern
is the template

`RevenueSnapshotScheduler` already avoids `@Scheduled`'s literal-zone limitation by using
`SchedulingConfigurer` and resolving the salon's real timezone from live Square data at each run
(`resolveSalonZone()`, calling `square.locationTimeZone()`). Every multi-tenant scheduled job
(`ProviderVisitScheduler`, `RevenueSnapshotScheduler`, and the six SMS automation schedulers —
`RepeatCustomerWinbackScheduler`, `LapsedCustomerWinbackScheduler`, `LeadFollowUpScheduler`,
`SameDayRebookingScheduler`, `SameDayRebookingGroupExpiryScheduler`, `SmsReplyFlowScheduler`) is
changed to: fetch all businesses with an active `square_connection` (or, for SMS-only jobs, an active
`twilio_sms_config`), and loop, running the existing per-business logic once per business inside an
explicit `CurrentBusinessContext.runAs(businessId, ...)` scope. ShedLock keys (`config/SchedulerLockConfig.java`,
table added `V64`) gain a `-business-{id}` suffix so Business A's nightly snapshot job doesn't block
Business B's behind the same lock name across the existing blue/green replicas.

**Gap found during verification, not covered by the above:** `RevenueSnapshotScheduler` and
`ProviderVisitScheduler` resolve their salon timezone (`resolveSalonZone()`/`resolveZone()`, calling
`square.locationTimeZone()`) **once, when the `SchedulingConfigurer` registers the job at process
boot** — correct for one business, wrong the instant a second business in a different timezone exists.
The "iterate all businesses" change above must re-resolve each business's zone **per invocation**, not
reuse a zone cached at startup. The two hardcoded `zone="America/Los_Angeles"` SMS winback schedulers
have the same underlying issue in a more visible form (a literal, not just a stale-cached, zone) and
should move to the same live-per-business-per-run resolution pattern rather than gaining a second
hardcoded zone per business.

### D10 — Financial calculation architecture: configuration first, strategy only if proven necessary

Per the commission-engine research: `TierCommissionEngine` is a concrete class with exactly one
caller (`SettlementPreviewService`), and **every business-rule constant already lives in
`salon_config`/`CommissionConfig`**, not as Java literals. Given salon B is described as having "a
slightly different cash calculation" with no further specifics provided:

- **If the difference is a different rate, threshold, cutoff, or tip-fee value** (e.g., flat 50% with
  no tiering, no $60 cutoff, zero card-tip-fee deduction) — this requires **zero new code**. Set
  `tier_service_threshold` above any realistic count (or `base_commission_rate = tier_commission_rate`)
  to disable tiering; set `card_tip_fee_rate = 0` if salon B's processor doesn't pass through a fee.
  This is the expected case and is fully covered by D6 alone.
- **If the difference is structural** (e.g., `cash_to_salon` isn't `collected − gross×rate`, or tips
  aren't card-fee-adjusted, or discounts aren't absorbed the same way) — extract a minimal
  `CommissionEngine` interface (`firstHalf`, `secondHalfFinal`) that `TierCommissionEngine` implements
  unchanged (zero behavior change for Business A), add a `commission_engine` discriminator column to
  `salon_config`, and let `SettlementPreviewService` resolve the implementation via a small factory.
  **This is not built in this change** — see Open Questions. Building a generic strategy interface
  with a single real implementation, before the second implementation's actual shape is known, is
  speculative generality the task instructions explicitly warn against. The extraction is a half-day
  of low-risk work once salon B's real rule is in hand; doing it blind risks guessing the wrong seam.

**Recommendation to resolve before implementation starts:** get salon B's actual cash-calculation
rule in writing (a worked example: gross X, collected Y, expected cash-to-salon Z) and classify it
against the two cases above before writing any commission code.

**Resolved.** Business B is **Anna Kara Brow Studio** ("AK PMU"), a separate Square Business, Pacific
timezone, ~2 providers. Owner confirmed in writing: flat **45% provider / 55% salon**, no tiering, no
tier threshold, no service-price cutoff distinction, **no no-show fee**. This is squarely the
config-only case above — `base_commission_rate = tier_commission_rate = 0.45` (or an equivalent
"tiering disabled" value) in AK PMU's own `salon_config` row is sufficient. **Zero change to
`TierCommissionEngine`.** `card_tip_fee_rate` for AK PMU's Square processor is not yet confirmed —
default to `0` until the owner confirms otherwise (it's additive to fix later, not a launch blocker).
The no-show-fee Open Question (former #6 below) is also resolved: AK PMU's `no_show_fee_amount`
setting stays `null`/off.

**Also newly relevant — the P0 payment-accounting fixes already shipped directly serve AK PMU's own
stated requirements.** AK PMU's own onboarding notes explicitly call out "Card + Cash" split payments
and "potentially several Card payments for one appointment" as scenarios needing a reliable
reconciliation model between appointments and Square payments — **this is exactly the class of bug
found and fixed against real Business A data** (see the standalone P0 fixes: custom-amount /
no-catalog-item line surfacing, and cash-note gap auto-matching against unattributed sales, both
shipped to production ahead of this change). Both fixes are channel-agnostic and business-agnostic
already — no AK-PMU-specific work needed here, they inherit for free once AK PMU's `business_id`
scoping lands.

### D11 — Feature configuration: three tiers, not a binary on/off

- **Core, always on, no flag:** commission engine, Square sync, settlements/reports, users/auth,
  providers, customers. Every business gets these.
- **Per-business toggle (flag lives on a new `business_feature` table, `business_id, feature_key,
  enabled`, replacing the current single global boolean env vars like `rag.enabled`,
  `ai.triage.enabled`):** SMS automation platform, Telegram notifications, RAG assistant, KB/SOPs,
  marketing/ads analytics, revenue forecasting, bank-statement reconciliation. A 2-provider salon
  plausibly wants Square sync + commission + reports and nothing else on day one — these features are
  **hidden, not deleted**, and can be switched on later without a deploy.
- **Platform-admin-only:** the `docker-swarm-secrets`-managed deployment-wide credentials
  (Anthropic/Voyage/LangSmith keys, Postgres), and the new `/api/platform/*` business-management
  endpoints from D4.

This directly answers the task's "a feature being unnecessary for Salon B does not automatically mean
it should be deleted" instruction.

### D12 — Frontend: session-carried business context, not a parameter threaded through ~30 proxy
routes

Per the frontend research: `serverApi.ts`/`proxyBackend.ts` already forward only `sid` (session) and
`role` cookies to the backend; every proxy route (`app/api/*`) is a thin pass-through with no
business-scoping parameter. **Decision:** add one more cookie, `businessId` (readable, non-httpOnly,
same tier as `role`), set at login/switch, refreshed on each proxied call exactly where `role` already
is (`proxyBackend.ts:25-31`). The backend derives the authoritative tenant from the *session*
(`CurrentBusinessContext`, D7), never trusts the cookie for authorization — the cookie only drives
which UI chrome/nav renders; **every actual data boundary is enforced server-side** (task requirement:
never rely on frontend filtering for tenant security). Proxy route signatures don't change. The
switcher itself is a new row in the existing `AdminMenu.tsx` dropdown (the dropdown-rendering block is
`AdminMenu.tsx:151-165`, corrected from an earlier 130-165 citation — lines 130-149 are the
notification-bell/messages-icon section, unrelated):
plain, non-interactive text for the ~100% case (single membership), a `<select>` only when
`>1` membership row exists for that user. `proxy.ts` (edge middleware) role-gating logic is unchanged.

### D13 — Rejected alternative: separate schema/database per business

Considered and rejected as the default for N=2 salons: Postgres schema-per-tenant (or DB-per-tenant)
gives the strongest physical isolation but (a) breaks Flyway's current single-schema migration model
across 83 files without a substantial rework, (b) requires per-tenant connection routing that this
Spring Boot app has no infrastructure for today, (c) makes cross-tenant platform-admin queries (e.g.
"list all businesses' last sync time") require fan-out instead of one filtered query, and (d) is the
kind of scaling investment appropriate at dozens-to-hundreds of tenants, not two. Revisit if a future
business has a hard compliance requirement for physical data separation (see Architecture Alternatives
below) — the `business_id` column design does not preclude moving a single business to its own schema
later if ever required.

## Risks / Trade-offs

- **`salon_config`'s 13 call sites are a wide, mechanical diff** — highest regression risk is a missed
  call site silently reading the wrong business's config (or failing `findById` and NPEing). Mitigate
  with a compile-time forcing function: delete `SalonConfigRepository.findById(Integer)` entirely once
  the rewrite starts, so every remaining call site fails to compile until fixed — safer than trusting
  a grep to be exhaustive.
- **Hibernate `@Filter` adds a small but real per-query overhead and a footgun** (filters must be
  explicitly enabled per Hibernate session — forgetting to enable it in a new code path silently
  returns unfiltered, i.e. cross-tenant, rows rather than erroring). Mitigate with an integration test
  that asserts the filter is enabled for every request by asserting on a representative sample of
  entities per request, not just relying on it being "on by default" (Hibernate filters are opt-in per
  session, not opt-out).
- **RAG vector search (`rag_chunk` nearest-neighbor query) is a real cross-tenant leak vector today**
  if two businesses' documents ever land in the same table without a filter — flagged as the single
  highest-severity finding from the domain-model research; the pgvector HNSW index query must include
  a `business_id` predicate before the similarity search, not after (as a WHERE on the ANN result),
  both for security and to avoid the ANN index returning another business's chunks and truncating the
  real result set.
- **`sms_automation`'s PK-as-config-key pattern (`automation_key` as the literal primary key) is the
  single hardest table to migrate** — six automations' enabled/disabled state, phone-number-keyed
  conversation threads (`sms_message`), and `blocked_number` (PK = phone number, globally) all assume
  one phone-number space per deployment. Recommendation: Business B gets its own Twilio number from
  day one (architecturally cleaner than tenant-scoping a shared number's inbound-webhook routing), and
  `sms_message`/`blocked_number` gain `business_id` with composite uniqueness
  (`business_id, phone_number`) rather than trying to preserve global phone-number uniqueness.
- **Two known businesses is a small N** — some of this design's generality (a full `business_feature`
  toggle table, a `BusinessMembership` join table for a case with zero current instances) costs more
  upfront than a hardcoded two-salon special case would. Justified because the task explicitly asks
  for an architecture "robust enough for future salons" and these two structures are cheap
  (a handful of tables, no new subsystems) relative to the alternative of redoing them under real
  multi-tenant load later.

## Migration Notes

- New Flyway migrations start at `V84`. Proposed grouping (see `tasks.md` for the exact sequence):
  `V84` creates `business` + backfills Business A; `V85` adds `business_membership` + backfills every
  existing `app_user` row into it; `V86` repurposes `salon_config` (drop `CHECK(id=1)`, add
  `business_id`); `V87` creates `square_connection` + backfills Business A's token/location from the
  current env vars (one-time manual data migration step, documented in tasks.md, **not** scripted
  into the migration itself since it involves a secret value); subsequent migrations add `business_id`
  to each table per the classification in the companion analysis document's Database Changes section,
  grouped by subsystem (payroll tables, SMS tables, RAG tables, marketing tables) so each can be
  reviewed and shipped as its own reviewable PR rather than one enormous migration.
- Every migration in this change is additive and backward-compatible until the final
  cutover migration that drops the old singleton constraints — Business A's application code keeps
  working against the old shape until the corresponding service-layer change ships in the same PR,
  per this repo's existing "stacked, small PRs" convention.
- Full backfill, rollback, and numeric-regression-verification procedure is in the companion analysis
  document's Migration Strategy section — reproduced in `tasks.md` as concrete, checkable steps.

## Open Questions

1. ~~Business B's exact cash-calculation rule~~ **Resolved** — see D10: flat 45/55, no tiering.
2. **Does AK PMU get its own Twilio number and Telegram bot, or share platform infrastructure?**
   **Owner's plan:** reuse the existing Twilio *integration code* for now; a dedicated AK PMU number is
   an explicit later TODO, not needed at launch. Telegram: not implemented for AK PMU at all yet
   (future separate group/chat). Both are moot for the initial migration regardless of this answer —
   SMS automation and Telegram notifications are `business_feature`-gated (D11) and default **off** for
   a newly onboarded business, so no shared-number routing problem exists until AK PMU actually
   requests SMS. Revisit D9's "own Twilio number" recommendation only when that happens.
3. ~~Does the platform owner need one login for both businesses~~ **Resolved: yes, required from day
   one** — see D4's update. The switcher UI is in scope for the first release, not deferred.
4. **Backfill of `merchant_id` on `square_connection`** — Square's Locations API returns it, so it can
   be fetched during Business A's backfill rather than left null; confirm this is desired versus
   leaving it null until the first live sync populates it. Still open.
5. ~~`CashNoteParser`'s bilingual keyword regex is a hardcoded convention~~ **Substantially
   de-risked, not fully closed.** AK PMU's own description of its cash workflow ("provider writes cash
   in notes, or does a full cash checkout in Square") is structurally identical to Business A's —
   and the existing regex (`square/CashNoteParser.java:28-29`) already matches bare `\bcash\b` as a
   whole word, not only the "cashew" spelling, so AK PMU's providers writing plain "cash $nn" would
   already parse correctly with **no code change**. Still verify against AK PMU's actual real notes
   once its Square is connected (Phase 7) before assuming this is fully closed — do not skip that
   check, just deprioritize building a configurable keyword list unless real data proves it's needed.
6. ~~`NoShowFeeService`'s flat $25 fee~~ **Resolved** — AK PMU doesn't charge a no-show fee; ships with
   `no_show_fee_amount = null` (feature off) for that business.
7. ~~`MerchantNormalizer.java` hidden Business-A-specific assumption~~ **Resolved, clean.** Read
   directly: purely generic regex prefix-stripping + a DB-backed `MerchantAlias` lookup table. The
   `SQ *AKLUXNAILS` string in its class doc comment is only an illustrative example, not logic. No
   special-casing needed — `MerchantAlias` just needs `business_id` like any other table in Phase 2.
8. **NEW — Deposits are a genuinely new pattern, not covered by anything above.** AK PMU takes
   card deposits (e.g. $100/procedure) weeks ahead of the appointment, which must net against the
   final total without double-counting revenue or commission. This is structurally similar to the
   cash-note-gap-matching fix (§D-adjacent, see the shipped P0 fixes) but **not the same**: a deposit
   can be weeks removed from the visit (the 2-day matching tolerance used for cash-note gaps doesn't
   apply), and Square may model deposits distinctly (Invoices API deposit fields) rather than as an
   ordinary orphan/custom-amount payment. Needs the same treatment as the original P0 investigation —
   real Square data once AK PMU is connected, before writing any deposit-handling code. Do not guess
   the mechanism blind; this is exactly the kind of assumption that produced the custom-amount and
   cash-note bugs in the first place. Gates Phase 7 (AK PMU onboarding), not Phase 1-3.
