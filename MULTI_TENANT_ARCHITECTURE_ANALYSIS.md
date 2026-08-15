# Multi-Tenant / Multi-Salon Architecture Analysis — salaryReview

**Status:** Analysis only. No code, migration, or UI has been changed. This document is the audit
trail behind `openspec/changes/multi-tenant-salon-platform/` (`proposal.md`, `design.md`, `tasks.md`,
`specs/multi-tenant-foundation/spec.md`), which is the actionable spec. This document is the longer-form
"why," grounded in file:line citations gathered by direct repository research, produced to satisfy the
requirement that the analysis "must be based on the actual repository," not generic architecture theory.

Repository analyzed: `/home/ubuntu/salaryReview-dev` (dev working copy; `~/salaryReview` is the live
deploy target — never edited directly, per project convention). Business A = the existing salon,
AK.LUX.NAILS, owner login `olexandr.kara2`.

---

## 1. Executive Summary

salaryReview is a Spring Boot 4 / Java 21 + Next.js 16 payroll-and-operations platform, currently
serving exactly one nail salon. It has grown well past "a commission calculator": 54 JPA entities, 83
Flyway migrations, and subsystems for Square reconciliation, tiered commission, SMS marketing
automation (6 automations via Twilio), a RAG knowledge assistant (pgvector), SOPs/KB content, bank
statement reconciliation, staff documents, and Telegram notifications shared with two sibling Next.js
apps via an internal API key.

**There is currently zero tenant concept anywhere in the system.** No table has a `business_id` or
equivalent column. The single `SquareClient` Spring bean is constructed once at boot from one
personal access token. The one table that already stores commission rates as configurable data
(`salon_config`) is a hard singleton (`CHECK (id = 1)`, `V1__init_schema.sql:33`), read via
`findById(1)` at 13 call sites. Every unique constraint in the schema assumes single-tenant
uniqueness. Every scheduled job assumes one implicit business end-to-end.

The good news, confirmed by deep code reading rather than assumed: **the core financial engine is
already correctly parameterized.** `TierCommissionEngine` (`commission/TierCommissionEngine.java`) has
zero hardcoded business-rule literals — every rate, threshold, and cutoff is read from
`salon_config`/`CommissionConfig`. Business B's "slightly different cash calculation" is, in the most
likely case, a config-values-only change requiring **zero code changes** to the commission kernel. Two
real hardcoded, salon-specific pieces of business logic *were* found that are not addressed by the
existing proposal and need to be classified before implementation: the bilingual cash-note parser
(§4.4) and the flat $25 no-show fee (§4.7).

**Recommendation:** add a `business_id` tenant boundary across the schema and application layer in
place — one codebase, one deployment, N businesses — rather than duplicating infrastructure per salon
or over-building a schema-per-tenant SaaS platform for two known customers. This is Option B in §17
below, and is what `openspec/changes/multi-tenant-salon-platform/design.md` already specifies in detail
(13 numbered decisions, D1–D13). This document independently verified that design's factual claims
against the code; §2–§16 below record what was confirmed, what was corrected, and what was newly
found. A consolidated list of every correction is in §22.

---

## 2. Current System Analysis

### 2.1 Backend architecture
Spring Boot 4.0.6, Java 21, Maven. Package layout under `backend/src/main/java/com/salonreview/`:

| Package | Responsibility |
|---|---|
| `ai/` | Anthropic Claude Haiku 4.5 triage service, prompts, structured outputs, LangSmith tracing |
| `commission/` | `TierCommissionEngine` — the commission kernel, plus `CommissionConfig` |
| `config/` | Security, Square/AI/Internal properties, `OwnerBootstrap`, `SchedulerLockConfig`, `HttpSessionConfig` |
| `domain/` | All 54 JPA entities (flat package — `kb/`, `sms/`, `marketing/`, `rag/`, `sop/` are service packages, not separate entity packages) |
| `kb/`, `marketing/`, `rag/`, `sms/`, `sop/`, `telegram/` | Feature-specific services |
| `repo/` | Spring Data repositories |
| `service/` | Legacy manual-entry calculator (`SettlementService`, pre-Square, marked for retirement) |
| `square/` | `SquareClient`, reconciliation (`SquareMonthAggregator`), schedulers, forecasting, `webhook/` subpackage |
| `util/` | Shared utilities |
| `web/` + `web/dto/` | REST controllers + DTOs |

No `@ManyToOne`/`@JoinColumn` object-graph conventions in most of `domain/` — see §6.1's important
correction on this, since it materially affects the tenant-filter design.

### 2.2 Frontend architecture
Next.js 16 App Router, React 19, TypeScript, Tailwind v4. Server components fetch via
`app/lib/serverApi.ts`; browser mutations go through `app/lib/api.ts` → same-origin proxy route
handlers under `app/api/*` which hold the httpOnly session cookie and forward to `BACKEND_URL` — the
browser never learns the backend host. A separate edge `proxy.ts` (Next 16's renamed `middleware`) does
its own hardcoded role-based path-prefix gating, independent of and parallel to the backend's
`SecurityConfig` — **two authorization surfaces that must be kept in sync**, not one.

### 2.3 Database
PostgreSQL 16, 83 Flyway migrations (`V1`–`V83`; **next is `V84`** — `openspec/config.yaml`'s embedded
project-context note claiming "next migration is V18" is stale and should be corrected). 54 `@Entity`
classes (not 65 — see §22 corrections). Zero pre-existing `business_id`/`tenant_id`/`salon_id` column
anywhere (verified via full-repo grep).

### 2.4 Authentication and authorization
Spring Security, form login (`POST /api/login`), server sessions **backed by Postgres via
`spring-session-jdbc`** (`config/HttpSessionConfig.java`, schema from `V59`) — not in-memory; this was a
deliberate fix so redeploys don't sign everyone out. Session cookie is forced to `JSESSIONID` (not
Spring Session's default `SESSION`) via a custom `CookieSerializer` because the frontend hardcodes that
name in 3 places. 30-day sliding expiry. Passwords: BCrypt.

Four roles: `OWNER`, `MANAGER`, `PROVIDER`, `ADS_MANAGER` (`domain/Role.java`). **Authorization is
centralized entirely in `config/SecurityConfig.java`'s ordered `authorizeHttpRequests` path-matcher
chain — there are no per-method `@PreAuthorize` annotations anywhere**, despite a stale class-javadoc
comment at `SecurityConfig.java:27` implying otherwise. The real matcher chain covers ~25 rules across
settlements, users, providers, RAG, SOPs, KB, SMS activity, time tracking, expenses, marketing — far
more than `openspec/config.yaml`'s embedded summary (3 roles, a handful of routes) documents; that
summary is stale and should be corrected (§22).

`AppUserPrincipal` (`config/AppUserPrincipal.java:18-51`): `userId`, `username`, `passwordHash`,
`role`, `providerId` (nullable, for PROVIDER self-scoping), `active`. **Zero business/tenant concept.**
`OwnerBootstrap` (`config/OwnerBootstrap.java`) is an `ApplicationRunner` that seeds exactly one OWNER
account from `APP_OWNER_USERNAME`/`APP_OWNER_PASSWORD` env vars, only if `app_user` is empty —
single-tenant by construction.

### 2.5 Users / roles / providers / customers
`AppUser` (login identity) and `Provider` (a salon staff member who earns commission) are **separate
entities** — a `Provider` may or may not have a login (`app_user.provider_id` links them when they do).
Customers are not a first-class local entity at all for most flows — the system reads customers live
from Square (`SquareClient.customerIdsForPhone`, etc.); `OwnerCustomer` is a local table only for
**comped** family/friend visits (`squareCustomerId` UNIQUE, no FK to anything — a root table).

### 2.6 Square integration
See §8 (dedicated section, since this is one of the most important subsystems).

### 2.7 Financial calculations
See §4 (dedicated section).

### 2.8 Synchronization / scheduled jobs
9 scheduled job classes total (§13.2's table). Two (`RevenueSnapshotScheduler`,
`ProviderVisitScheduler`) use `SchedulingConfigurer` with **live per-boot timezone resolution** from
Square's own location data (`resolveSalonZone()`/`resolveZone()`, calling
`SquareClient.locationTimeZone()`) rather than a literal `zone=` string — a real, already-correct
pattern. Two SMS winback schedulers (`RepeatCustomerWinbackScheduler:115`,
`LapsedCustomerWinbackScheduler:86`) use `@Scheduled(cron=..., zone="America/Los_Angeles")` — an
**explicit but hardcoded** literal zone, with inline comments referencing a real 2026-08-07
unzoned-cron production incident (matches the operator's own memory of a cron firing SMS at 3am instead
of 10am). The remaining five SMS jobs are `fixedDelay` polling loops (zone-agnostic by construction).
**No job today loops over "all X of something"** — there is no existing multi-entity-iteration
precedent in the scheduler layer to build on; every job assumes exactly one implicit business
end-to-end, and every cron-based job needs to move from "one fixed/resolved zone at boot" to
"resolved per business per run" for a second, differently-timezoned business (§8, correction to
design.md D9).

### 2.9 Caching
Documented in `docs/CACHING.md` and confirmed to match the real `SquareClient` implementation exactly:
an in-process `ConcurrentHashMap<String, Cached<?>>` with per-data-type TTLs (bookings/orders 10 min,
team members 5 min, catalog 10 min, location timezone 1 h, customer-phone lookups 5 min,
customer-bookings 2 min), a `Semaphore(6, fair=true)` outbound concurrency throttle, an honest
"last real Square fetch" timestamp shown in the UI (never a misleading "just now"), and a manual
`POST /api/sync` that calls `invalidate()`. **`docs/CACHING.md` states explicitly: "The cache is
in-memory per backend instance... is process-wide/shared across users, and is not tied to login."**
This is today's clearest concrete instance of global singleton state — confirmed, not assumed.

### 2.10 Background workers / internal API
`InternalNotificationController` (`/api/internal/**`, `permitAll()` at the Spring Security layer, its
own `X-Internal-Api-Key` header check via `MessageDigest.isEqual`) exposes 3 endpoints
(`notifications/four-hand-request`, `notifications/sms/send`, `rebooking-promo/enroll`) called by two
sibling Next.js apps, `mani`/`salonLandings` and `akluxnails-home`, sharing Telegram notification
infrastructure. **This key is process-global with zero business-scoping** in any request shape today —
flagged as a real (if currently low-severity, since only Business A has sibling apps) security gap in
§13.

### 2.11 Tests, seed data, scripts, deployment
114 backend test classes (not 23 — README's count is stale; see §22), heaviest coverage in `square/`
(34 classes — the financial-calculation regression suite) and `sms/` (24 classes). Coverage gaps: most
`web/` controllers have **no dedicated MockMvc test** (no `ProviderControllerTest`,
`PrepaidControllerTest`, `RedoControllerTest`, `SettlementPreviewControllerTest` found) — relevant to
§14's testing strategy, since these are exactly the endpoints that most need new cross-tenant-isolation
coverage. Single-VPS deployment (`salon.spincareer.com`), **no staging environment** (confirmed by
README and `docs/DEPLOY.md`), GitHub Actions CI (`mvn verify` against a real ephemeral Postgres service
so Flyway runs for real, plus frontend `tsc`/`next build`) gating a blue/green rolling deploy on push to
`master`. Nightly `pg_dump` backups, 7-day local + 30-day Drive retention via `rclone`. This deploy
pipeline has already survived and been hardened against three real documented incidents (uncommitted
WIP leaking into a build, Compose skipping recreate on unchanged images, a swallowed SSH heredoc) — the
scheduler lock-key-suffix requirement in §13.2 is protecting against an already-proven bug class in this
exact deployment machinery, not a hypothetical.

---

## 3. Current Single-Salon Assumptions (the actual grep results)

Confirmed by direct repository search — every category the task asked for:

- **Hardcoded salon identity in the schema, not code:** `salon_config.owner_short_name` defaults to
  `'AK'` at the DB level (`V1__init_schema.sql:34,37`), consumed by `service/MessageFormatter.java:13-30`
  to build the literal payout message ("Zelle AK to Anna: $284.55"). This is already correctly
  data-driven — once `salon_config` is per-business, this "just works" with no further fix.
- **Hardcoded salon identity in frontend code:** `frontend/app/owner/marketing/period.ts:28` —
  `const SALON_TIME_ZONE = 'America/Los_Angeles';`, used at line 42 for an `Intl` timezone option. A
  real single-salon assumption that must become business-supplied data.
- **Hardcoded business-rule literals that are not in `salon_config` (new findings, not previously
  identified):**
  - `NoShowFeeService.java:50` — `FEE = new BigDecimal("25.00")`, with a `$24–26` tolerance band
    (`FEE_MIN`/`FEE_MAX`, lines 51-52) used to *detect* a no-show fee order by matching amount.
  - `square/CashNoteParser.java:28-29` — `CASH_KEYWORD = Pattern.compile("con налич|cashew|\\bcash\\b",
    ...)`, a bilingual (English + Russian) regex encoding this specific salon staff's actual note-taking
    convention.
  - `RetentionAnalyticsService.java:37,39-40` — `RETENTION_WINDOW_DAYS = 60` (explicitly commented
    "configurable later" in the code itself), `LEAK_MIN_NEW = 3`, `LEAK_RETENTION = 0.40`.
- **Global singleton config tables (fixed-PK or boolean-PK pattern), confirmed exact mechanism:**
  - `salon_config`: `id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1)` — `V1__init_schema.sql:33`.
  - `twilio_sms_config`, `telegram_notification_config`, `marketing_sync_status`: identical
    `id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id)` pattern — `V46:9`, `V45:9`, `V50:8`.
  - `sms_automation`: primary key **is** the literal automation name — `automation_key TEXT PRIMARY KEY`
    (`V52:9`).
  - `blocked_number`: primary key **is** the phone number — `phone_number TEXT PRIMARY KEY` (`V61:5`).
  - `rag_agent_config`: not a strict singleton row, but enforces one active config platform-wide via
    `CREATE UNIQUE INDEX uq_rag_agent_config_active ON rag_agent_config (active) WHERE active`
    (`V25:21`).
- **Global singleton Square client:** `SquareClient` is a `@Component` constructed once at boot
  (`SquareClient.java:36,47`) from `SquareProperties` (one `accessToken`, one `locationId`, one
  `environment`). Its cache map and `Semaphore(6)` throttle are instance-level state shared by every
  request regardless of caller — confirmed in §2.9.
- **`salonConfig.findById(1)` — 13 confirmed call sites** (exact list, verified against the real files):
  `MarketingContactsService.java:681`, `ProviderDirectory.java:37`, `MarketingAnalyticsService.java:1290`,
  `RevenuePulseService.java:214`, `PrepaidService.java:225`, `CancelledAppointmentService.java:247`,
  `ProviderVisitIngestService.java:158`, `OwnerOverviewService.java:112`,
  `RevenueSnapshotService.java:260`, `SuspiciousBookingService.java:368`,
  `SettlementPreviewService.java:275`, `SettlementPreviewService.java:388`, `SettlementService.java:50`.
- **Unique constraints assuming global single-tenant uniqueness** (need to become composite with
  `business_id`): `app_user.username`, `pay_periods(year, month, half)`, `revenue_snapshot(snapshot_date)`,
  `owner_customer.square_customer_id`, `no_show_fee_override.square_booking_id`,
  `suspicious_booking_clearance.square_booking_id`, `cancellation_clearance.square_booking_id`,
  `suspicious_triage(square_booking_id, prompt_version)`, `marketing_contact_square_link.phone_number`,
  `lead_followup_send.contact_id` (+composite), `lapsed_customer_winback_send.square_customer_id`,
  `same_day_rebooking_send.square_payment_id`, `ad_spend(year, month)`,
  `manager_time_entry.user_id WHERE end_at IS NULL`,
  `settlement_feedback(provider_id, year, month, half)`, `sop_acknowledgments(sop_version_id, user_id)`,
  `provider_visit(customer_id, provider_ref, service_date)`. Note: Square-assigned IDs
  (`square_booking_id`, `square_customer_id`, `square_payment_id`) are globally unique across all Square
  merchants platform-wide (Square assigns them, not this app), so raw *collision* risk between Business
  A and B is negligible — the need here is query correctness/isolation discipline, not collision
  avoidance.
- **Structural finding with real design impact: almost no `@ManyToOne`/`@JoinColumn` associations
  exist.** Of the 54 entities, only `PeriodEntry` (→ `provider_id`, `pay_period_id`) and `Provider`'s
  `@CollectionTable` use real JPA object associations. Everything else — `TierGrant.providerId`,
  `Redo.originalProviderId`/`redoProviderId`, `ManualAdjustment.providerId`, `PrepaidRedemption.packageId`
  /`providerId`, `ManagerPayRate.userId`, `ManagerTimeEntry.userId`, `SopAcknowledgment.sopVersionId`
  /`userId`, `StaffDocument.providerId`/`appUserId`, `RagChunk.documentId`, `SopVersion.sopId`, etc. —
  uses plain `Long` ID columns with no mapped association. **This directly affects the tenant-isolation
  design**: Hibernate's `@Filter` mechanism (the defense-in-depth layer `design.md` D8 proposes) only
  auto-cascades through real object-graph JOINs; a raw `Long providerId` field is not a mapped
  association, so there's no automatic JOIN for a Hibernate filter to ride along on. **The FK-inheritance
  category ("tenant scope inherited via FK," design.md/tasks.md's category (b)) is therefore not a free
  defense-in-depth layer for ~52 of these 54 entities the way it would be in a codebase using real JPA
  associations everywhere.** Recommendation, which changes the DB Changes section below from what
  `design.md`/`tasks.md` currently scope: **give every business-owned table its own direct `business_id`
  column rather than relying on FK-inheritance**, even where a parent FK exists. It's more migration
  columns, but it removes an entire class of "forgot to join" bugs, and matches how this codebase
  already models relationships (raw ID columns, not JPA associations) rather than fighting that pattern.
- **No table found that should remain purely globally shared.** Every table is either
  salon-operational data or salon-configurable config. (`SPRING_SESSION`, from Spring Session JDBC's own
  schema, `V59`, is infrastructure, not domain data — irrelevant to business-data isolation since
  sessions already carry the authenticated user.)

---

## 4. Business Logic Analysis (how money is actually calculated today)

### 4.1 Commission engine — `commission/TierCommissionEngine.java`

**First half of month** (`firstHalf`, L38-65) — always pays base rate, never speculatively tiered:
```
zelle_to_provider = cardRevenue × baseRate + tipsAfterCardFee + adjustments
cash_to_salon      = cashCollected − cashGross × baseRate
```

**Second half / month close** (`secondHalfFinal`, L74-129) — reconciles the whole month:
```
qualified         = tierGrant ?? (monthServiceCount >= cfg.tierServiceThreshold())   // manual override wins
tierBonus         = qualified ? (h1.cardRevenue + h2.cardRevenue) × tierUplift : 0    // whole-month card
cashTierRebate    = qualified ? (h1.cashGross   + h2.cashGross)   × tierUplift : 0
zelle_to_provider = h2.cardRevenue × baseRate + tipsAfterCardFee + adjustments + tierBonus
cash_to_salon      = cashCollected − cashGross × baseRate − cashTierRebate
```
where `tipsAfterCardFee = cardTips × (1 − cardTipFeeRate)` (L131-133) — tips are never commissioned,
paid in full net only of the card processing fee. `cash_to_salon` can go negative (the salon pays cash
back to the provider) — this is the mechanism behind the README's "no-clawback" claim: the provider is
never asked to return money.

**Correction to the README's simplified formula:** the README states the qualified-half rate as
"`effective_rate × cardRevenue`" with `effective_rate` swapped to `tierRate`. The actual code always
multiplies by `baseRate` and adds `tierBonus` as a separate additive term — numerically equivalent
(`tierBonus = cardRevenue × (tierRate − baseRate)` when qualified) but mechanically different. Anyone
re-deriving this formula should read the code, not the README.

All four inputs (`baseRate`, `tierRate`, `tierServiceThreshold`, `cardTipFeeRate`) plus
`servicePriceCutoff` (the $ floor for a line to count toward the tier) come from
`SalonConfig.toCommissionConfig()` (`domain/SalonConfig.java:37-39`), a 1:1 unparameterized mapping —
**zero hardcoded rate/threshold/cutoff literal anywhere in `commission/` or `square/`** (confirmed by
grep). This is the single most important finding for Business B: **if its cash-calculation difference
is a different rate, threshold, cutoff, or tip-fee value, it requires zero new commission code** — only
different values in its own `salon_config` row.

### 4.2 Settlement assembly — `SettlementPreviewService.java` (681 lines)

`preview(year, month)` (L273-328) is the entry point — **contains one of the 13 `findById(1)` call
sites at line 275**, throwing `IllegalStateException` if the singleton row is missing. Flow:
`SquareMonthAggregator.aggregate()` (bookings joined to orders) → provider collapse
(`collapseToPersons`) → four synthetic-line channels folded in via `applyExtraLines` (L220-236, in
order): **prepaid draw-downs**, **redos**, **manual adjustments**, then **no-show fees** separately via
`applyNoShowAdjustments` (L303-305, folded as `adjustments`, never commissioned revenue).

Channel semantics (all Java process logic, not config-driven):
- **PREPAID** (L94-126): pays the provider on menu price, discount surfaced as
  `menuPrice − (packageAmount / totalServices)` — confirms the README's "salon absorbs discounts" claim:
  discount is tracked/reported but never subtracted from what the provider is paid on.
- **REDO** (L136-155): a signed pair — the redo provider gains commission, the original provider loses
  it, both dated in the redo period, **never clawed back from the already-paid original period**
  (L130-133).
- **MANUAL** (L167-184): owner-entered credit/deduction; counted-unit sign follows `gross.abs() >= cutoff`
  then `gross.signum()` — an explicit bug-avoidance comment (L162-166) about negative adjustments never
  being able to decrement the tier count.
- **NOSHOW**: flat `$25` fee (§4.7), folded as `adjustments`.

**Judgment: this assembly logic should remain global code, not per-business config.** These are
process/workflow rules (how a prepaid redemption or a redo folds into a settlement), not tunable
numbers — very unlikely any salon wants prepaid/redo/adjustment *semantics* to differ. If Business B
doesn't use prepaid packages or redos, the corresponding maps are simply empty
(`Map.of()` returned early), already degrading gracefully to a no-op with zero special-casing needed.

### 4.3 Square reconciliation — `square/SquareMonthAggregator.java`

Joins bookings (who/what/when) to orders (money, no provider) on customer+service+day. Cash-note
bookings (no Square checkout, a note left instead) become synthetic cash services (L136-138), with
double-counting avoided by tracking notes that also matched a real checkout separately
(`cashNotesSkipped`, L239).

### 4.4 Cash-note parsing — `square/CashNoteParser.java` (57 lines) — **the highest-priority unresolved business-logic question**

```
CASH_KEYWORD = Pattern.compile("con налич|cashew|\\bcash\\b", CASE_INSENSITIVE | UNICODE_CASE)
AMOUNT       = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)")
```
If the Russian stem matches (`налич*`, covering наличные/наличка/наличными), the amount is **always**
taken from the appointment's service total — per an in-code comment, this style of note never carries
an explicit number (L43-44). If the English keyword matches ("cashew $nn" — a deliberate, slightly
unusual phrasing this specific salon's staff use), the `AMOUNT` regex extracts a literal dollar figure
(L46-48). A note matching neither keyword is silently ignored (returns `Optional.empty()`), by explicit
design (L21-22 doc comment — presumably to avoid over-matching arbitrary customer notes as cash
declarations).

**This is not a numeric config value — it's a hardcoded convention two specific humans agreed on**,
and it is **not addressed anywhere in `openspec/changes/multi-tenant-salon-platform/design.md`'s
existing table classification or Open Questions.** It is added as a new open question in §22/§16.
Recommend either: (a) a per-business on/off flag (some salons may transact 100% through Square's own
cash-tender checkout with no note-parsing convention needed at all), or (b) a per-business configurable
keyword list, not a shared hardcoded regex. This should be resolved with the same urgency as Business
B's commission-rate values (§9's Open Question 1), since it directly affects whether Business B's cash
revenue is even correctly detected.

### 4.5 Net profit — `OwnerOverviewService.netRevenue()` (L471-479)

```
netProfit = gross − cardPayroll − cashPayroll − expenseTotal − cashBusinessExpenseTotal − managerLaborCost
```
(all-or-null — any missing input nulls the whole result, deliberately "unknown" rather than a
misleadingly partial number). `gross = card + cash`. `cardPayroll`/`cashPayroll` normally come from a
real `SettlementPreviewService` result, but for the **current, not-yet-closed month**, `fromSquare`'s
live-estimate path (L267-269) **re-derives commission independently**:
`gross × baseCommissionRate + tips × (1 − cardTipFeeRate)` — using only the base rate, never the tier.
**This is a second, simplified, duplicated copy of the commission formula living outside
`TierCommissionEngine`.** If Business B's real commission engine differs (§9), this fallback needs the
same treatment or it will silently misreport live net profit for Business B's current month even after
the real engine is correctly configured/extended.

Expenses (`expenseTotalForMonth`, `managerLaborCostForMonth`, `cashBusinessExpenseTotalForMonth`,
`personalBankTotalForMonth`, `ownerDrawsTotalForMonth`) resolve from `ExpenseService`, sourced either
from a reconciled bank-statement import (`ExpenseImportService`, when the period is statement-covered)
or manual `ExpenseEntry` rows. This is a whole separate subsystem (`ExpenseService.java`,
`ExpenseImportService.java`, `MerchantRuleEngine`, `MerchantNormalizer`, `CsvStatementParser`) that is
clearly per-business financial data with its own significant table surface — flagged for the DB Changes
table (§11) but not examined line-by-line in this pass.

`profitAfterPersonal = netProfit − personalBankTotal − ownerDrawsTotal` — a further layer for money the
owner personally drew from the business.

**Judgment: the P&L formula shape is global code**; what differs per business is simply which rows
exist, already handled by the tenant-scoping columns. The one real risk is the duplicated fallback
formula noted above.

### 4.6 Retention / rebooking — `RetentionAnalyticsService.java`

All computed in-memory from the `ProviderVisit` ledger, no SQL aggregation. "Rebooked" =
`ProviderVisit.isRebookedSameDay()`, computed at ingestion. "New to provider" = first visit with that
specific provider in the target month. Cohort retention (`provRet`/`salonRet`, L107-118) = fraction of
that cohort returning within `RETENTION_WINDOW_DAYS` (60, explicitly commented "configurable later" —
also explicitly a nail-salon-specific "one rebook cycle" assumption) — either to the same provider or
anywhere in the salon. A month's retention is reported as unknown, not zero, until the 60-day window has
actually elapsed (`matured`, L82). "Leak" flag (L120-121): `newToSalonViaProvider >= 3 (LEAK_MIN_NEW)
&& provRet < 0.40 (LEAK_RETENTION)`.

**Judgment: config-per-business candidate, low priority** (analytics/alerting, not money-critical).

### 4.7 No-show fees — `NoShowFeeService.java`

Flat `$25.00` (L50), detected by matching a Square order in a `$24.00–$26.00` tolerance band
(L51-52,79,157) to absorb minor tax/rounding variance; owner/manager can manually `confirm()`/
`suppress()` per booking. Folded into settlement as a flat adjustment (§4.2), never commissioned.

**Judgment: config-per-business, currently 100% hardcoded, not addressed in the existing proposal.**
A 2-provider salon may not charge no-show fees at all, or charge a different flat amount, or a
percentage. Recommend the same treatment as `salon_config`: a nullable `no_show_fee_amount` business
setting (null/zero disables the feature).

### 4.8 Revenue forecasting — `RevenueForecastService.java`

Blends two independent signals: a historical first-half/second-half split-ratio pattern and a
calibrated booking-ceiling bias learned from past `month_end_actual` snapshot rows. Weighting shifts
from 50/50 (≤5 months of calibration history) to 30/70 favoring calibration (6+ months) — algorithm
tuning constants, not business rules. Confirms the README's "widens its range when signals disagree"
claim: the dual-signal case uses min/max of the two signals for its range bounds (not a
blend-of-ranges), producing an asymmetric ±10%/±9% band.

**Judgment: global code, per-business data** — purely statistical, self-calibrating from each
business's own history.

### 4.9 Summary table

| Calculation | Classification | Notes |
|---|---|---|
| Commission engine (`TierCommissionEngine`) | **Config only, zero code change expected** | Fully parameterized via `salon_config`; confirms `design.md` D6/D10's premise directly |
| Settlement assembly (prepaid/redo/manual/no-show folding) | **Global code** | Process logic; degrades gracefully to no-op if a channel is unused |
| Cash-note parsing (`CashNoteParser`) | **Needs strategy/config — new open question** | Hardcoded bilingual regex is one salon's human convention, not universal |
| Net profit formula (`OwnerOverviewService`) | **Global code**, with one real risk | The live-month fallback (§4.5) duplicates a simplified commission calc — must track whatever Business B's engine decision turns out to be |
| No-show fee ($25 flat + detection band) | **Config-per-business, currently hardcoded — new open question** | Not in `salon_config` today; should be |
| Retention window/thresholds (60d, 3, 0.40) | **Config-per-business, low priority** | Already commented "configurable later" in the source |
| Revenue forecasting | **Global code** | Self-calibrating per business, no business-specific literals |

---

## 5. Multi-Tenant Architecture

See `openspec/changes/multi-tenant-salon-platform/design.md` for the full, decision-by-decision
specification (D1–D13). This section summarizes and cross-references, correcting where this research
found discrepancies (full list in §22).

**Core mechanism:** a new `business` table is the tenant root. Every business-owned table gains a
`business_id` — per §3's finding above, this analysis recommends a **direct column on every
business-owned table**, not reliance on FK-inheritance through raw `Long` ID columns, which is a
correction/tightening of `design.md`/`tasks.md`'s current table classification (their category (b) —
"inherits scope through FK" — is not automatically safe here the way it would be with real JPA
associations).

**Request-scoped resolution:** a `CurrentBusinessContext` bean, populated once per request in a
servlet filter right after Spring Security authenticates, exposing the caller's business id — avoiding
the literal `if (salon == A)` anti-pattern by resolving context once rather than threading a
`businessId` parameter through ~150 repository/service methods. Since sessions are Postgres-backed
(`spring-session-jdbc`, §2.4), the resolved business id could be cached directly in session attributes
at login rather than re-resolved via a join on every request — a possible optimization not yet reflected
in `design.md`, worth considering during implementation.

**Enforcement, two independent layers (defense-in-depth):** (1) every tenant-scoped repository method
explicit about `business_id` (e.g. `findByBusinessIdAndUsername` replacing `findByUsername`), and (2)
a Hibernate `@Filter` enabled per-session from `CurrentBusinessContext` as a second layer — with the
important caveat from §3 that this filter only auto-applies where a real JPA association exists, so
most of these 54 entities need it applied directly to their own table, not inherited.

**Background jobs** iterate all businesses with an active relevant connection (Square, Twilio),
executing existing per-business logic once per business under an explicit
`CurrentBusinessContext.runAs(businessId, ...)` scope; ShedLock keys gain a `-business-{id}` suffix. See
§8's correction: the two `SchedulingConfigurer`-based schedulers currently resolve timezone once at
process boot, not per invocation — this needs explicit handling for a second, differently-timezoned
business, which `design.md` D9 doesn't currently call out.

---

## 6. Domain Model

### 6.1 Terminology: `Business`

The primary domain noun is **`Business`** — table `business`, column `business_id` everywhere, Java
type `Business`. "Tenant" is used only in prose to describe the isolation pattern, never as a class or
column name. Rationale (unchanged from `design.md` D1): "Salon" bakes an accidental assumption into
platform vocabulary; "Tenant" reads as generic SaaS jargon in code owners/managers never see;
"Organization" implies a multi-location/multi-brand umbrella that doesn't exist for either known
business today. `SalonConfig` is repurposed (D6), not renamed, to keep the tenant-bearing migration
diff reviewable — a pure rename is deferred to a follow-up cleanup PR.

### 6.2 Entity relationships (target shape)

```
Business
├── BusinessMembership (join table: business_id, user_id, role) ── AppUser (login identity)
├── SquareConnection (1:1 — access token, location id, environment)
├── SalonConfig (1:1 — commission rates/thresholds/cutoffs)
├── BusinessFeature (business_id, feature_key, enabled — per-business feature toggles)
├── Provider[] (direct business_id)
├── PayPeriod[] (direct business_id) → PeriodEntry[] (via provider/pay-period FK)
├── RevenueSnapshot[] (direct business_id)
├── OwnerCustomer[], SuspiciousBookingClearance[], CancellationClearance[], SuspiciousTriage[]
│     (direct business_id — no reliable FK path, keyed by Square IDs only)
├── TierGrant[], Redo[], ManualAdjustment[], PrepaidPackage[]/PrepaidRedemption[],
│     SettlementFeedback[], ManagerPayRate[], ManagerTimeEntry[], StaffDocument[]
│     (direct business_id, per §3/§6.1's FK-inheritance correction)
├── SMS subsystem: TwilioSmsConfig, SmsAutomation, SmsMessage, BlockedNumber
│     (direct business_id; BlockedNumber's PK moves from phone_number alone to
│      (business_id, phone_number) — see §9's hardest-migration note)
├── RAG subsystem: RagDocument, RagChunk, RagAgentConfig, RagSuggestionCache, RagRedactionAudit
│     (direct business_id; RagChunk is the flagged highest-severity leak vector — §13)
├── Marketing subsystem: FunnelAnalysis, AdSpendEntry, MarketingContactSquareLink, etc. (direct business_id)
├── Expense subsystem: BankStatementImport, BankTransaction, ExpenseEntry, ExpenseCategoryDefinition,
│     MerchantRule, MerchantAlias (direct business_id)
└── KB/SOP subsystem: KbArticle, KbRequest, Sop, SopVersion, SopAcknowledgment (direct business_id)
```

No table was found that should remain purely global/platform-shared (§3).

### 6.3 Why `BusinessMembership`, not a bare `app_user.business_id` column

No existing case of a user needing more than one business was found. A direct column would be simpler.
But `app_user` already carries login credentials that are naturally per-*person*, and the one plausible
near-term multi-membership case — the platform owner wanting to see both Business A and Business B
without two logins — is exactly a multi-membership case. A join table costs one extra table/query today
and avoids a much more disruptive later migration (splitting login identity from business role). A
user's `activeBusinessId` is resolved at login from their membership row(s): the sole row for ~100% of
users today (zero UX change), a picker only if `>1` row exists.

### 6.4 Square connection cardinality

`SquareClient` structurally threads a single `locationId` through every call, and no code path handles
multiple locations. Both known businesses are single-location. `square_connection` is modeled 1:1 with
`business`, not 1:N — if a future business needs multiple physical locations, that becomes a child
`square_location` table, deliberately not built speculatively now.

---

## 7. Authentication and Authorization

### 7.1 Role model recommendation

Keep the 4 existing roles (`OWNER`, `MANAGER`, `PROVIDER`, `ADS_MANAGER`) as roles scoped to a
**business membership row**, not global — they already mean exactly "manage one business" through
"operational view of one business," so no redesign is needed. Add a narrow, separate `platform_admin`
flag (a small table keyed by `user_id`, not a 5th `Role` enum value) for the platform owner managing
both salons' onboarding, gating only a handful of new `/api/platform/*` endpoints.

**Rejected:** a `GLOBAL_ADMIN` role enum value — every existing `hasRole`/`hasAnyRole` matcher across
`SecurityConfig`'s ~25 rules would need re-auditing for whether a global admin implicitly passes it,
which is exactly the kind of blanket-privilege bug multi-tenant systems get bitten by. A separate,
additive, narrowly-scoped check keeps all existing business-scoped authorization untouched.

### 7.2 Confirmed vs. corrected claims

- Confirmed: 4 roles exactly as `design.md` states, centralized route gating (not per-method), Postgres
  session store, `AppUserPrincipal`'s exact field list.
- **Correction:** `design.md` D7 cites `SuspiciousTriageController` as an example of the principal
  already being pulled from context — **this class does not exist**. The real class is
  `web/SuspiciousBookingController.java:46`. The broader claim (principal-from-context is an established
  pattern via `@AuthenticationPrincipal AppUserPrincipal`, used in ~15+ controllers) is correct; only
  the specific citation is wrong.
- **New finding, not previously flagged:** `openspec/config.yaml`'s embedded project-context block
  (shown to AI on every artifact-generation call) is stale on both the role model (misses
  `ADS_MANAGER`) and the route list (misses ~20 of the ~25 real matcher rules) — worth a housekeeping
  fix independent of the multi-tenant work.
- **New security finding:** the `internal.api.key` mechanism (§2.10) has zero business-scoping in any
  request shape today. Low severity today (only Business A has sibling apps calling it), but any future
  reuse of this pattern for Business B needs an explicit business identifier validated per-call, not a
  shared process-global secret alone. See §13.

---

## 8. Square Integration

### 8.1 Current architecture, confirmed

`SquareProperties` doc comment (quoted exactly, `config/SquareProperties.java:12-14`): *"The access
token is a secret and must come from the environment, never from committed config. Phase 1 uses a
single personal access token for the salon's own Square account; Phase 2 replaces this with per-merchant
OAuth tokens."* `SquareClient` is a true singleton `@Component`, one `RestClient` built at construction
with a fixed bearer token baked in — structurally cannot hold two businesses' credentials simultaneously.

### 8.2 Corrections to prior claims (important — feeds directly into implementation scope)

- **Not strictly read-only.** `addCustomerToGroup` (`SquareClient.java:696`, PUT) and
  `removeCustomerFromGroup` (`SquareClient.java:707`, DELETE) are real mutating calls against Square's
  customer-groups endpoint, used by the same-day-rebooking-discount SMS automation. No financial object
  (payment, order, booking) is ever written — the "read-only" characterization should be narrowed to
  "never writes anything financial," not "never writes."
- **The `SquareClient` call-site list is materially larger than previously scoped.** Direct injectors of
  `SquareClient` number **35+ files**, not ~10: beyond the originally-cited
  `ManagerTimeService`/`NoShowFeeService`/`PrepaidService`/`ProviderVisitScheduler`
  /`ProviderVisitIngestService`/`RevenuePulseService`/`RetentionAnalyticsService`
  /`RevenueSnapshotService`/`RevenueSnapshotScheduler`/`SquareMonthAggregator`/`UserController`, add
  `SquareBookingFilters`, `SquareSpikeService`, `SuspiciousBookingService`, `CancelledAppointmentService`,
  `OwnerCustomerService`, `OwnerOverviewService`, `SettlementPreviewService`,
  `InternalNotificationController`, `MarketingAdsReportController`, `SquareSyncController`,
  `OwnerOverviewDto`, the entire `marketing/` package (`FunnelAnalyticsService`,
  `MarketingAnalyticsService`, `MarketingContactsRepository`, `MarketingContactsService`,
  `MarketingDashboardService`), the entire `square/webhook/` package, and 5 of the 6 SMS schedulers.
  **The `SquareClientProvider` call-site migration is a meaningfully bigger diff than previously
  scoped** — worth re-estimating effort for that phase.
- **Scheduler timezone resolution happens once at process boot, not per invocation.** Both
  `SchedulingConfigurer`-based schedulers (`RevenueSnapshotScheduler`, `ProviderVisitScheduler`) resolve
  their zone once when the scheduler registers at startup, from whichever business's `SquareClient`
  singleton exists at that moment. This is correct for one business but **becomes wrong the instant
  there are two businesses in two timezones** — a real gap not previously called out. The
  "iterate all connected businesses" plan (design D9) needs to explicitly re-resolve zone per business
  **per run**, not reuse one zone cached at boot.
- **No retry/backoff exists anywhere in `SquareClient`.** The `Semaphore(6)` only bounds concurrency; a
  `429`/transient 5xx from Square propagates as an unhandled exception in most callers (a few, like
  `bookingsForCustomer`'s internal fan-out, degrade to partial results — most don't). This isn't a
  regression the multi-tenant change introduces, but a second business is a second independent way to
  hit the exact rate-limit-related production incident `docs/CACHING.md` already documents having
  happened once (100+ simultaneous requests tripping Square's limit, crashing the Analytics tab).
  Per-business `SquareClient` instances via `SquareClientProvider` fix the shared-cache/shared-throttle
  problem "for free" (each instance gets its own cache and its own semaphore) — but note this means the
  effective process-wide outbound-concurrency budget becomes N×6, not 6, once there are N businesses;
  harmless against Square's *per-merchant* rate limit (each business is a different Square merchant) but
  worth stating explicitly.
- **Webhooks confirmed global today:** one `SquareWebhookController` endpoint
  (`POST /api/public/webhooks/square`, `permitAll()`, HMAC-SHA256 signature check against one global
  key), handling only `payment.updated`. Moving to `/api/public/webhooks/square/{businessId}` with a
  per-path signature-key lookup (verified before trusting any payload field, never trusting the
  unauthenticated `merchant_id` field pre-verification) is the correct fix.
- **`merchant_id` confirmed absent everywhere** — zero references in the backend. Square's
  `/v2/locations/{id}` response does include it, but `SquareClient.Location`'s record
  (`@JsonIgnoreProperties(ignoreUnknown=true)`) silently discards it today. Capturing it during
  onboarding requires no new API surface, just one new field on that record — this confirms (rather than
  contradicts) the existing Open Question about whether to backfill it.

### 8.3 Design implication

`SquareClient`'s internals (cache, throttle, TTL logic) do not need to change at all — correctness for
multi-tenancy falls out entirely from "one instance per business," constructed via a new
`SquareClientProvider` registry/factory keyed by `businessId`, replacing the single `@Component`
singleton. Credentials move from process env vars to a new `square_connection` table (encrypted access
token, location id, environment, nullable merchant id, one row per business) — mechanically the same
personal-access-token model as today, just per-business instead of per-process. Square OAuth
(authorization-code flow, refresh tokens, App Marketplace review) remains explicitly out of scope for
this change, per `docs/ROADMAP.md`'s own stated phase ordering.

---

## 9. Financial Rules Architecture

**Recommendation: configuration first, strategy only if proven necessary — do not build a generic
strategy interface speculatively.**

Given the commission engine is already a pure function of 5 config values with zero hardcoded literals
(§4.1), classify Business B's actual cash-calculation difference into one of two cases before writing
any code:

1. **If the difference is a different rate, threshold, cutoff, or tip-fee value** (e.g. flat 50% with no
   tiering, no $60 cutoff, zero card-tip-fee deduction) — **zero new code.** Disable tiering by setting
   `tier_service_threshold` above any realistic count, or set `base_commission_rate = tier_commission_rate`;
   set `card_tip_fee_rate = 0` if Business B's processor doesn't pass through a fee. This is the expected
   case.
2. **If the difference is structural** (e.g. `cash_to_salon` isn't `collected − gross×rate`, tips aren't
   card-fee-adjusted, or discounts aren't absorbed the same way) — extract a minimal `CommissionEngine`
   interface (`firstHalf`, `secondHalfFinal`) that `TierCommissionEngine` implements unchanged (zero
   behavior change for Business A, verified against the regression snapshot in §15), add a
   `commission_engine` discriminator column to `salon_config`, resolve via a small factory in
   `SettlementPreviewService`. **Do not build this speculatively** — the extraction is roughly half a
   day of low-risk work once Business B's real rule is known; guessing the seam wrong before knowing the
   actual difference risks building the wrong abstraction.

**Newly required, not previously scoped:** the same config-first-strategy-only-if-needed decision
applies to two more hardcoded business rules found in this research, neither currently in
`salon_config`: the no-show fee amount (§4.7, currently `$25.00` in Java) and the cash-note parsing
convention (§4.4, currently a hardcoded bilingual regex). Recommend resolving all three (commission
difference, no-show fee, cash-note convention) together as a single "get Business B's actual numbers in
writing" exercise before implementation reaches this phase — see §16's Open Questions.

**The net-profit live-estimate fallback** (§4.5, `OwnerOverviewService.fromSquare`) independently
re-derives a simplified commission calculation and must be updated in lockstep with whatever Business
B's commission-engine decision turns out to be, or it will silently misreport Business B's current-month
net profit even after the real settlement path is correctly configured.

---

## 10. UI/UX Proposal

**Architecture: session-carried business context via a cookie, not a URL segment or a parameter
threaded through ~30 proxy routes.**

`app/lib/proxyBackend.ts:15-33` and `app/lib/serverApi.ts:52-57` today forward exactly two cookies to
the backend: `sid` (httpOnly, holds the session id) and `role` (non-httpOnly, drives only UI/edge-routing
— confirmed never trusted for backend authorization). Add a third, `businessId`, at the same tier as
`role`, set at login/switch and refreshed on each proxied call the same way `role` already is. **The
backend derives the authoritative tenant from the session (`CurrentBusinessContext`), never trusts the
cookie for authorization** — the cookie only drives which UI chrome/navigation renders, satisfying the
task's "never rely on frontend filtering for tenant security" requirement directly, since every real
data boundary is enforced server-side regardless of what the cookie says.

**Business switcher:** a new row in the existing `AdminMenu.tsx` dropdown. **Correction to the line
range previously cited:** the actual dropdown-rendering JSX block is lines **151-165**
(`{open && (...)}`), not 130-165 — lines 130-149 are the notification-bell/messages-icon section,
unrelated. For the ~100% case (a user with exactly one `BusinessMembership` row — every real user today),
this row renders as **plain, non-interactive text**: zero visual change from today. A `<select>` +
switch endpoint appears only when a user has more than one membership row — deferred entirely if the
Open Question about whether the platform owner needs a working switcher on day one (§16) resolves to
"log in separately is fine for now."

`proxy.ts` (the separate edge-middleware authorization gate, §2.2) needs no changes for this — it gates
*which pages render*, not *which data* they show, and business context doesn't change which pages a
role can reach.

**Navigation by role**, current route tree confirmed from `frontend/app/`:
- **OWNER-only**: `/reports`, `/owner/overview`, `/admin/users`, `/admin/prepaid`, `/admin/owner-customers`,
  `/admin/manager-time`, `/admin/documents`, `/rag/admin`, `/sops/admin`
- **OWNER+MANAGER**: `/manager`, `/admin/redos`, `/owner/retention`, `/admin/manual-adjustments`
- **PROVIDER-only**: `/me` and children
- **ADS_MANAGER-only**: `/owner/marketing` and children exclusively
- **Shared/authenticated**: `/kb`, `/sops`, `/my-documents`
- **Unauthenticated**: `/` (landing + login)

None of these routes currently encode business identity in the URL — consistent with the cookie-based
approach being the right fit; retrofitting `/business/{id}/...`-style URLs would touch every route for
no isolation benefit (isolation is enforced server-side regardless).

**Onboarding, confirmed fully absent today:** no UI or backend code anywhere for connecting Square or
creating a business — Square credentials are 100% `.env`-driven, and `/admin/users` (the one existing
admin UI) operates entirely within the single implicit business. A new `/onboarding` flow (platform-admin
creates the `business` row; its first OWNER logs in and pastes a Square personal access token + location
id, invites the first MANAGER/PROVIDER users) formalizes what is today a fully manual deploy-time
process — a genuinely new surface, not a retrofit.

**Feature visibility:** a newly onboarded business with no optional features enabled sees navigation
only for commission/settlements/Square-connected functionality — no SMS/RAG/marketing menu items
rendered — while the underlying code for those features is unchanged and enablable later without a
deploy. This directly satisfies "a feature being unnecessary for Salon B does not automatically mean it
should be deleted."

---

## 11. Database Changes

Full per-table classification is in §3 (unique constraints) and the entity-relationship sketch in §6.2.
Summary of the change categories:

- **New tables:** `business`, `business_membership`, `square_connection`, `business_feature`,
  `platform_admin`.
- **Repurposed table:** `salon_config` — drop `CHECK (id = 1)`, add `business_id`, backfill Business A's
  row. No change to any column meaning or to `TierCommissionEngine`.
- **~50 existing tables gain a direct `business_id` column** (per §3's correction: prefer a direct
  column over relying on FK-inheritance, given the raw-`Long`-ID-column pattern used throughout
  `domain/`). Grouped by subsystem for reviewable, independently-shippable migrations: payroll/settlement
  core, SMS automation, RAG, marketing/ads, expenses/bank-reconciliation, KB/SOP, staff documents.
- **Composite-unique constraints replacing global-unique ones**, full list in §3 — the two hardest are
  `sms_automation`'s literal-PK-as-config-key pattern and `blocked_number`'s phone-number-as-PK pattern
  (recommend Business B gets its own Twilio number from day one, avoiding the need to tenant-scope a
  shared number's inbound-webhook routing entirely).
- **New candidate config fields on `salon_config` or a sibling table**, not previously scoped: a
  nullable `no_show_fee_amount`, and either a cash-note-parsing on/off flag or a per-business keyword
  list (§4.4, §4.7).
- **Migration numbering:** starts at `V84` (confirmed; not `V18` as `openspec/config.yaml`'s stale
  context claims). Migrations should stay additive (new tables, new nullable-then-backfilled columns)
  until the specific PR that removes an old singleton/global-unique constraint in the same change as the
  code that stops relying on it, so any single PR can be reverted independently.
- **Forcing-function recommendation:** delete `SalonConfigRepository.findById(Integer)` entirely once
  the `salon_config` rewrite starts, so all 13 call sites fail to compile until fixed — safer than
  trusting a grep to be exhaustive for a change this wide.

---

## 12. API Changes

Every controller and its endpoints, current role gate, IDOR risk, and required change is inventoried in
full in `openspec/changes/multi-tenant-salon-platform/` (this analysis's companion research produced a
per-endpoint table covering all ~40 controllers; it is not reproduced in full here to keep this document
readable — see the Testing/Security sections below for the highest-priority subset).

**Highest-priority IDOR targets** (an `{id}`/`{bookingId}`/`{phoneNumber}` path or query parameter
taking a raw identifier with no ownership check today, since there is currently only one business so no
such check has ever been needed):
1. `UserController` (`/api/users/{id}`) — an OWNER of Business A could, once Business B exists, read/
   edit/delete another business's login credentials by id if the business check is missed.
2. `RagAdminController`/`RagController` — vector similarity search itself is the IDOR-equivalent risk;
   see §13.
3. `SmsActivityController` — phone numbers are the de facto identifier for 16 endpoints and are **not**
   globally unique once Business B has its own Twilio number.
4. `StaffDocumentController`/`StaffDocumentSelfController` — file downloads by id.
5. `ExpenseImportController` (`/{id}/file`) — bank-statement file downloads by id.
6. `ProviderController`, `PrepaidController`, `RedoController`, `NoShowFeeController`,
   `SuspiciousBookingController`, `OwnerCustomerController`, `ManualAdjustmentController`,
   `TierGrantController`, `KbArticleController`, `SopController`, `MerchantRuleController`,
   `ManagerTimeController` — every one takes at least one raw entity id today.

**Genuinely new endpoints** (no prior equivalent): `POST /api/platform/businesses`,
`GET /api/platform/businesses`, `POST /api/platform/businesses/{id}/suspend` (platform-admin only),
`POST /api/square/connection` (onboarding), `POST /api/business/switch` (only if the multi-membership
switcher ships in the first release).

**No-change endpoints** (naturally scope correctly once repositories are business-scoped, no
id-ownership risk because they take no path id): `MeController`, `OwnerOverviewController`,
`RevenuePulseController`, `RetentionController`, `MarketingAnalyticsController`,
`MarketingContactsController`, `FunnelAnalysisController`, `SettlementSelfController` (scoped via the
caller's own principal, though this must be re-derived through business context too — a provider's
`providerId` needs checking against `activeBusinessId`, not just role).

**Webhook/public endpoints requiring business-path routing:** `SquareWebhookController`
(`/api/public/webhooks/square/{businessId}`, §8.2), plus (found but not previously enumerated) Twilio's
inbound-SMS/status/voice webhook controllers and `ShortLinkController` (`/r/**`) — same
signature-per-business-key treatment needed once Business B has its own Twilio number.

---

## 13. Security

### 13.1 Cross-tenant isolation strategy

Two independent, defense-in-depth layers (§5): explicit `business_id` predicates in every repository
method (primary), plus a Hibernate session-level `@Filter` (secondary) — applied per-table directly
(§3's correction), not relied upon to cascade through FK joins, since the codebase's raw-`Long`-ID-column
pattern means most tables have no mapped association for a filter to ride along on. Native/
`@Query(nativeQuery=true)` queries (e.g. `TrafficSourceSql`, `MerchantRuleEngine`'s `pg_trgm` queries)
bypass Hibernate filters entirely — explicit `business_id` predicates are mandatory in those paths
specifically, not optional.

### 13.2 Highest-severity finding: RAG vector search

`RagChunk`'s pgvector nearest-neighbor query is a real cross-tenant leak vector the moment two
businesses' documents exist in the same table without a filter — and per §3's structural finding,
`RagChunk` has only a raw `documentId` Long column, no mapped association, so it needs its own direct
`business_id` and an explicit predicate **before** the ANN similarity search runs, not as a WHERE clause
applied after (which would both leak data and truncate the real result set, since the HNSW index would
return another business's chunks and never reach the true top-K within the caller's own business).

### 13.3 Other confirmed security findings

- **`internal.api.key` has zero business-scoping** (§2.10, §7.2) — a real gap if this mechanism is ever
  reused for Business B's sibling apps; low severity today since only Business A has any.
- **`POST /api/sync` (manual cache-bust) has no role check today** — any authenticated user can trigger
  it; low severity for one business, becomes relevant once it must scope to "only the caller's
  business's `SquareClient` cache instance."
- **Authorization is 100% centralized in `SecurityConfig`'s path matchers, with zero per-method
  `@PreAuthorize`.** This means the tenant-scoping work is the *only* new authorization layer being
  added — it is not fighting against or duplicating an existing method-security layer, which simplifies
  the design (one well-understood chain to extend, not two to reconcile).
- **`MerchantNormalizer.java`** (expense-reconciliation subsystem) was flagged as needing direct
  verification that its logic doesn't assume Business A's specific Square merchant name during bank
  statement reconciliation — not confirmed either way in this pass; recommend a direct read before this
  subsystem is tenant-scoped.

### 13.4 Recommendations

- Cross-tenant read → `404`, never `200`-with-wrong-data or `403`-revealing-existence.
- Cross-tenant write → rejected, no row modified, same non-leaking response shape.
- Every `{id}`-taking endpoint in §12's list gets an explicit ownership-check integration test, not just
  a code review pass.
- A representative-sample integration test asserting the Hibernate filter is actually *enabled* per
  request — filters are opt-in per Hibernate session, not opt-out, so a forgotten `enableFilter()` call
  in a new code path silently returns unfiltered (cross-tenant) rows rather than erroring.

---

## 14. Testing Strategy

Confirmed test inventory: **114 backend test classes** (README's "23" figure is stale — see §22),
heaviest in `square/` (34 — the financial-calculation regression suite: checkout attribution, cash-note
parsing, tip allocation, prepaid draw-downs, redos, no-show fees, owner comps, `SquareClient`
concurrency/cache behavior, revenue snapshot/forecast/pulse, retention analytics, suspicious-booking
detection) and `sms/` (24 — schedulers, Twilio inbound/status/voice, media, reactions, templates,
short-link routing). **Coverage gap confirmed:** most `web/` controllers have no dedicated MockMvc test
today (no `ProviderControllerTest`, `PrepaidControllerTest`, `RedoControllerTest`,
`SettlementPreviewControllerTest` found) — exactly the endpoints most in need of new cross-tenant
coverage, so this isn't just "add tenant tests," it's "add controller tests that didn't exist before,
tenant-scoped from the start."

**Unit tests:** `TierCommissionEngineTest`/`CommissionCalculatorTest` are the regression gate pinning
the commission formula (§4.1) — must pass unchanged with only fixture wiring changes (a `businessId`/
`SalonConfig` resolved via context instead of a literal `1`), never a behavior change.

**Regression tests (the single most important test in the whole change):** capture Business A's last 6
closed months' `/api/settlements/preview` output per provider/month and `/owner/overview` net-profit
figures as a golden snapshot *before* Phase 1 starts. After each subsequent phase, re-run the same
requests against the migrated schema and assert byte-for-byte identical `BigDecimal` output — any diff
blocks merge.

**Multi-tenant isolation tests:** stand up Business A's real fixture plus a synthetic Business B in the
same test database (not mocked); assert every settlement/report/user/provider/suspicious-booking/RAG
endpoint scoped to A never returns a B row and vice versa, under real repository queries. A dedicated
RAG vector-search cross-tenant test (§13.2) — Business A's uploaded documents never surface in Business
B's chat-assistant results.

**Authorization tests:** an OWNER of Business A calling any `{id}`-taking endpoint from §12's
high-priority list with a Business-B-owned id gets `404`, never `200` or a `403` that reveals existence.

**Migration tests:** verify the `SalonConfigRepository.findById(Integer)` deletion (§11's
forcing-function) leaves zero remaining call sites — a grep-based compile-time check, not just trust in
manual review.

**UI tests:** Playwright coverage for both the single-membership (no switcher visible) and
multi-membership (switcher renders, switch works) cases; onboarding flow end-to-end against Square
sandbox credentials.

**Manual verification per repo convention:** `docker compose up -d --build`, log in as the existing
Business A owner, confirm `/reports`, `/me`, `/owner/overview` render numerically identical to before
the change.

---

## 15. Migration Strategy

1. **Additive-only migrations** (new tables; new nullable-then-backfilled columns) until the specific PR
   that drops an old singleton/global-unique constraint ships in the same change as the code that stops
   relying on it — so any single migration PR can be reverted independently without leaving the schema in
   a broken intermediate state.
2. **`V84`** creates `business`, inserts Business A's row (derived from `salon_config.owner_short_name =
   'AK'`, e.g. `short_code = 'akluxnails'`).
3. **`V85`** creates `business_membership`, backfills one row per existing `app_user` pointing at
   Business A.
4. **`V86`** repurposes `salon_config` (drop `CHECK(id=1)`, add `business_id`), backfills Business A's
   existing row.
5. **Delete `SalonConfigRepository.findById(Integer)`**, fix all 13 resulting compile errors one file per
   commit, each verified against the §14 regression snapshot before moving to the next.
6. **`V87`** creates `square_connection`; a **manual, documented, non-Flyway one-time step** (it involves
   moving a live secret) migrates `SQUARE_ACCESS_TOKEN`/`SQUARE_LOCATION_ID` from the current env vars
   into Business A's row.
7. **Subsequent migrations, grouped by subsystem** (payroll, SMS, RAG, marketing, expenses, KB/SOP), each
   its own reviewable PR — direct `business_id` columns per §3/§11's correction, not FK-inheritance.
8. **Verification at every step:** re-run the §14 regression snapshot; any `BigDecimal` diff blocks
   merge. This is how "existing reports continue to return the same numbers" gets proven, not assumed.
9. **Rollback:** every phase-1-through-4 migration is independently revertible per point 1. The
   `salon_config` `findById(1)` removal is the one genuinely all-or-nothing step per file, deliberately
   split into 13 small commits (one per call site) specifically so a single bad one can be reverted
   without reverting the whole migration.
10. **Production rollout:** deploy `V84`+ during a maintenance window on the existing blue/green
    pipeline (§2.11), immediately re-run the regression comparison against production data, confirm
    existing scheduled jobs (SMS automations, revenue snapshots) still fire correctly for Business A, and
    only then onboard Business B — after at least one full day/night cycle running clean in production.

---

## 16. SaaS Readiness

**Required now:** the `Business`/`BusinessMembership`/`SquareConnection`/`BusinessFeature` domain shape
(§6), tenant-scoped data access (§5, §13), per-business Square credentials (§8), per-business commission
config (§9), a minimal onboarding flow (§10).

**Easy to add later, not built now:** Square OAuth (authorization-code flow, refresh tokens, App
Marketplace listing — explicitly the documented next phase per `docs/ROADMAP.md`, mechanically
compatible with the `square_connection` table shape this change introduces), a real multi-business
switcher UI (schema supports it via `BusinessMembership`'s multi-row case; UI can ship later without a
schema change), a `commission_engine` discriminator/strategy interface (§9, extract only once Business
B's real rule is known), per-business branding/landing pages (Business B has no stated landing-page
requirement).

**Do not build yet:** billing/subscriptions/Stripe integration, public self-signup, a marketing signup
funnel, complex enterprise SSO, usage limits/metering — none needed for two known, owner-invited salons,
and none of the schema decisions above make adding them materially harder later (a `business` row with
an `active` flag is already a reasonable seam for a future `subscription_status` column, without
building subscription logic now).

---

## 17. Architecture Alternatives

**Option A — Minimal patch (branch on business identity where needed).** Lowest short-term effort, but
directly violates the task's explicit anti-pattern (`if (salon == A) ... if (salon == B) ...`), doesn't
scale past 2 businesses, and provides no structural isolation guarantee — rejected.

**Option B — Tenant-aware domain architecture in the existing codebase (recommended).** A `business_id`
boundary added across the existing schema/application layer, one codebase, one deployment. Moderate
effort (a wide but mechanical migration — §11's ~50-table column addition, mitigated by grouping into
per-subsystem reviewable PRs), low-to-moderate migration risk (mitigated by the additive-until-cutover
strategy in §15 and the golden-snapshot regression gate in §14), strong security via the two-layer
defense-in-depth model (§13), good maintainability (no new subsystems invented — existing SMS/RAG/
commission code gets a tenant key, not a rewrite), and directly SaaS-ready for a handful more businesses.
**This is the recommended option.**

**Option C — Full SaaS platform rearchitecture** (schema-per-tenant or DB-per-tenant, billing/
subscription infrastructure, public signup, a genuinely separate platform-admin service). Considered and
rejected as the default for N=2 known salons: schema/DB-per-tenant breaks Flyway's current
single-schema migration model across 83 files without substantial rework, requires per-tenant connection
routing this Spring Boot app has no infrastructure for today, makes cross-tenant platform-admin queries
(e.g. "list every business's last sync time") require fan-out instead of one filtered query, and is the
kind of scaling investment appropriate at dozens-to-hundreds of tenants, not two. The `business_id`
column design (Option B) does not preclude moving a single business to its own schema later if a hard
compliance requirement for physical data separation ever emerges — it's a strictly easier starting point
to evolve *from*, not a dead end.

**Recommendation: Option B**, optimizing for simple now + correct architecture + easy future expansion,
not maximum theoretical scalability — matching the task's own stated optimization target.

---

## 18. Recommended Architecture

Option B (§17), specified in full, decision-by-decision detail in
`openspec/changes/multi-tenant-salon-platform/design.md` (D1–D13) and its testable requirements in
`specs/multi-tenant-foundation/spec.md`. This analysis independently verified that design's factual
claims against the code and found it substantively correct, with the corrections consolidated in §22
now applied back into those documents (see the accompanying edits made alongside this document).

---

## 19. OpenSpec Proposal

The formal OpenSpec proposal is `openspec/changes/multi-tenant-salon-platform/proposal.md` (Why / What
Changes / Non-Goals / How This Is Verified), with the full technical design in `design.md` and testable
requirements with scenarios in `specs/multi-tenant-foundation/spec.md`. This document is the supporting
analysis those artifacts reference. Reading order for a reviewer: this document (why the design looks
the way it does, grounded in the actual code) → `proposal.md` (the tight summary) → `design.md` (every
decision, with rationale and rejected alternatives) → `spec.md` (testable scenarios) → `tasks.md` (the
phased execution plan, §20 below).

---

## 20. Implementation Plan

Full phase-by-phase detail (exact files, migrations, dependencies, tests, rollback strategy per phase)
is in `openspec/changes/multi-tenant-salon-platform/tasks.md`. Summary:

| Phase | Objective | Main Changes | Dependencies | Risk |
|---|---|---|---|---|
| 0 | Discovery closeout | Get Business B's real cash-calc rule, no-show fee, and cash-note convention in writing (§9, §16); confirm Twilio/Telegram infra decision; confirm switcher-UI timing; review/approve proposal+design | None | Low — no code touched |
| 1 | Domain/database foundation | `business`, `business_membership` tables; `CurrentBusinessContext`; `salon_config` repurposed (V84–V86); `findById(1)` deleted at all 13 sites | Phase 0 sign-off | Medium — wide mechanical diff, mitigated by per-call-site commits + regression snapshot |
| 2 | Tenant-aware core tables | `business_id` on providers/app_user/pay_periods/revenue_snapshot + all FK-owned tables (direct column per §3/§11's correction); cross-tenant isolation test suite | Phase 1 | Medium — largest table-count phase |
| 3 | Square multi-account support | `square_connection`, AES-GCM token encryption, `SquareClientProvider`, ~35 call-site migration (§8.2's corrected scope), webhook per-business routing, scheduler per-business-per-run zone resolution (§8.2's new finding) | Phase 1–2 | Medium-High — larger call-site count than originally scoped |
| 4 | Business-specific financial config | Resolve §9's Open Question (config-only vs. structural commission difference); `business_feature` table; resolve the no-show-fee and cash-note-parsing open questions (§4.4, §4.7 — new, not in original scope) | Phase 0's worked example | Low if config-only (expected case); Medium if structural |
| 5 | Auth/authorization | Business-creation-time `OwnerBootstrap` replacement; narrow `platform_admin` flag; `/api/platform/*`; per-`{id}`-endpoint authorization tests (§12/§14) | Phase 1 | Medium — IDOR surface is wide (§12) |
| 6 | UI/business context | `businessId` cookie; `AdminMenu.tsx` switcher row (lines 151-165, corrected); `SALON_TIME_ZONE` hardcode fix; onboarding flow | Phase 3, 5 | Low-Medium |
| 7 | Business B onboarding | Create business row, connect Square, enter config, invite ~2 providers, enable only wanted features, shadow-run one real pay period before treating as authoritative | Phase 1–6 | Medium — first real second-tenant validation |
| 8 | Regression + security testing | Golden-snapshot comparison at every phase; full isolation suite; RAG cross-tenant test; security-review skill run before merge | Continuous from Phase 1 | — |
| 9 | Production rollout | Deploy during maintenance window; verify scheduled jobs; onboard Business B only after a full clean day/night cycle; update stale docs (`DEPLOY.md`, `ROADMAP.md`, `openspec/config.yaml`) | Phase 1–8 | Low if 8 is green |

---

## 21. Risks

- **Wide mechanical diff on `salon_config`'s 13 call sites** — highest single-point regression risk if
  a call site is missed. Mitigated by the compile-time forcing function (§11).
- **Hibernate `@Filter` footgun** — opt-in per session, not opt-out; a forgotten `enableFilter()` call
  silently returns unfiltered rows rather than erroring. Mitigated by an integration test asserting the
  filter is actually enabled (§13.4). **Made materially more important by §3's structural finding** that
  most entities need the filter applied directly, not inherited via FK-join.
- **`SquareClientProvider`'s call-site migration is larger than originally scoped** (~35 files, not
  ~10) — re-budget Phase 3's effort accordingly (§8.2).
- **RAG vector search is the single highest-severity cross-tenant leak vector** if `RagChunk` doesn't get
  a pre-search `business_id` predicate (§13.2).
- **`sms_automation`/`blocked_number`'s PK-as-config-key/PK-as-phone-number patterns are the hardest
  single migration** — recommend Business B gets its own Twilio number from day one rather than
  tenant-scoping a shared number's inbound routing.
- **Two known businesses is a small N** — some of this design's generality (a full `business_feature`
  toggle table, a `BusinessMembership` join table for a currently-zero-instance case) costs more upfront
  than a hardcoded two-salon special case would. Justified by the explicit requirement for an
  architecture "robust enough for future salons," and both structures are cheap relative to redoing them
  under real multi-tenant load later.
- **Two newly identified hardcoded business rules with no config path today** (cash-note parsing
  convention, no-show fee amount) could silently mis-detect Business B's cash revenue or no-show charges
  if not resolved before Phase 4 (§4.4, §4.7, §9).
- **Scheduler zone resolution is boot-time, not per-run**, for the two `SchedulingConfigurer`-based jobs
  — a real gap for a second, differently-timezoned business not previously identified (§8.2).
- **No retry/backoff in `SquareClient`** — a second business is a second independent way to reproduce the
  documented Square-rate-limit production incident; not introduced by this change but worth hardening
  alongside it given the increased exposure.

---

## 22. Open Questions

1. **Business B's exact cash-calculation rule is not yet known** — needed before Phase 4. Get a worked
   numeric example (gross X, collected Y, expected cash-to-salon Z) from the owner.
2. **Business B's no-show fee policy is not yet known** — does it charge one at all, and if so how much
   (flat vs. percentage)? (New — not previously identified.)
3. **Business B's cash-note-taking convention (if any) is not yet known** — same bilingual regex, a
   different convention, or does it not use cash-note bookings at all? (New — not previously identified.)
4. **Does Business B get its own Twilio number and Telegram bot, or share platform infrastructure?**
   Recommendation is "own number" (§21) — needs owner confirmation given real per-month cost.
5. **Does the platform owner need to view both businesses under one login on day one**, or is logging in
   separately to each acceptable initially? Affects whether the multi-membership switcher UI needs to be
   working in the first release or can ship schema-only with the UI deferred.
6. **Backfill of `merchant_id` on `square_connection`** — confirmed fetchable from the already-called
   `/v2/locations/{id}` endpoint (§8.2) by adding one field to an existing record; confirm this is
   desired versus leaving it null until the first live sync populates it.
7. **`MerchantNormalizer.java`'s internals were not directly verified** for a hidden Business-A-specific
   merchant-name assumption during bank-statement reconciliation (§13.3) — recommend a direct read before
   the expense-reconciliation subsystem is tenant-scoped.
8. **Consolidated list of corrections made to existing `openspec/changes/multi-tenant-salon-platform/`
   artifacts by this analysis** (also applied directly to those files):
   - Entity count: **54**, not 65.
   - Test class count: **114**, not 23 (README is also stale on this).
   - `SquareClient` call-site count for Phase 3.5: **~35 files**, not ~10.
   - Square integration is **not strictly read-only** — two customer-group mutation methods exist
     (never anything financial).
   - `design.md` D7's `SuspiciousTriageController` citation is wrong — the real class is
     `SuspiciousBookingController`.
   - `AdminMenu.tsx`'s switcher-insertion line range is **151-165**, not 130-165.
   - The two `SchedulingConfigurer`-based schedulers resolve timezone **once at boot**, not per
     invocation — a gap for a second, differently-timezoned business that D9 needs to explicitly address.
   - Migration numbering is confirmed **V84 next** (this was already correctly stated in `design.md`,
     but `openspec/config.yaml`'s separate embedded context block is stale, claiming V18 — a
     housekeeping fix independent of this change).
   - The FK-inheritance table-classification category should be **narrowed/mostly collapsed into
     "needs a direct column"** given the raw-`Long`-ID-column pattern used throughout `domain/` — only
     `PeriodEntry` has a real JPA association a Hibernate filter can cascade through automatically.
   - Two additional hardcoded, salon-specific business rules not previously scoped: the cash-note parser
     (§4.4) and the flat no-show fee (§4.7) — both need the same "get real numbers, classify config vs.
     code" treatment as the commission-rate Open Question.

---

## 23. Final Recommendation

Proceed with Option B (§17/§18) as specified in `openspec/changes/multi-tenant-salon-platform/`, with
the corrections in §22 applied. The core financial engine is already well-designed for this — it was
built as configuration-driven from the start (`salon_config`, `V4`'s own migration comment: "replacing
the values that were previously hardcoded"), so the multi-tenant work is genuinely a matter of adding a
`business_id` boundary around already-sound logic, not a rewrite. The main real risks are breadth (a
mechanical but wide schema diff) and two newly-found hardcoded business rules that need real-world
values from Business B before implementation can safely reach the financial-configuration phase. Get
those numbers, review the proposal/design/tasks documents with the owner (Phase 0), then proceed
phase-by-phase per §20, with the golden-snapshot regression gate (§14) enforced at every step.

---

## Appendix: Summary Tables

### Area / Current State / Required Change / Priority / Risk

| Area | Current State | Required Change | Priority | Risk |
|---|---|---|---|---|
| Tenant identity | None anywhere in schema | New `business` table, `business_id` on ~50 tables | Critical | Medium (breadth) |
| `salon_config` | Hard singleton, `CHECK(id=1)` | Per-business row, 13 call sites rewritten | Critical | Medium (mitigated by forcing-function + regression snapshot) |
| Commission engine | Fully config-parameterized already | None expected (config values only, pending Open Q 1) | High (blocking) | Low |
| Cash-note parsing | Hardcoded bilingual regex | Business-scoped config/flag (new finding) | High (blocking Business B correctness) | Medium |
| No-show fee | Hardcoded $25 flat | Business-scoped config (new finding) | Medium | Low |
| Square client | Process-global singleton | Per-business `SquareClientProvider` registry | Critical | Medium-High (35+ call sites) |
| Square webhooks | One global HMAC key | Per-business path + key | High | Medium |
| Scheduled jobs | Assume one implicit business; 2 resolve zone once at boot | Iterate all businesses; re-resolve zone per run | Critical | Medium (new gap found) |
| Auth/roles | 4 roles, no business scoping | Roles scoped to `BusinessMembership`; narrow `platform_admin` | Critical | Medium (wide IDOR surface) |
| Frontend session | `sid`+`role` cookies only | Add `businessId` cookie, server-enforced only | High | Low |
| UI navigation | Single-business, no switcher | Business-context row, hidden for 1-membership users | Medium | Low |
| RAG vector search | No isolation | Direct `business_id` predicate pre-ANN-search | Critical (highest-severity leak) | High if missed |
| SMS/Twilio | Global phone-number-keyed tables | Own Twilio number per business + composite keys | High | High (hardest migration) |
| Internal API key | No business-scoping | Per-business identifier once reused for Business B | Low today | Low today |
| Testing | 114 tests, gaps in controller coverage | Golden-snapshot regression + new isolation/IDOR tests | Critical | — |
| Onboarding UX | Fully absent, `.env`-only | New `/onboarding` flow | Medium | Low |

### Phase / Objective / Main Changes / Dependencies / Risk

See §20's table (reproduced there with full detail per phase).
