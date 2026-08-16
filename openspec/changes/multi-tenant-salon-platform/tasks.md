Ordering follows repo convention: backend (migration → domain/repo → service/engine → controller →
security) before frontend (proxy route → api.ts → page/component), then tests and verification, per
phase. Nothing in Phase 0–1 is implemented until the proposal/design above are reviewed and approved —
this file is the plan, not yet executed.

## Phase 0 — Discovery closeout (this change)

- [x] 0.1 Get Business B's exact cash-calculation rule in writing — **resolved**: AK PMU (Anna Kara
      Brow Studio) is flat 45%/55%, no tiering, no no-show fee. Config-only, D10.
- [ ] 0.2 Confirm Business B's Twilio/Telegram infra decision — **resolved as "not needed yet"**: reuse
      existing integration code, no dedicated number until AK PMU requests SMS features (which are
      `business_feature`-off by default at onboarding). No action item until that request happens.
- [x] 0.3 Confirm whether the platform-owner multi-business switcher UI is needed in the first release
      — **resolved: yes, required** ("Super Admin" needs one login across all businesses). Elevates
      Phase 6.2 from optional to required scope.
- [ ] 0.4 Review/approve `proposal.md` and `design.md` with the owner before any migration is written
- [x] 0.5 Get Business B's no-show fee policy — **resolved: none, `no_show_fee_amount = null`.**
- [x] 0.6 Get Business B's cash-note-taking convention — **substantially resolved**: same two-mechanism
      pattern as Business A (note or full cash checkout); existing `\bcash\b` regex already matches
      plain "cash," not just "cashew." Verify against AK PMU's real notes once connected (Phase 7) —
      keep as a lightweight verification step, not a blocking unknown.
- [x] 0.7 Read `MerchantNormalizer.java` directly — **resolved, clean**: purely generic
      regex + DB-backed `MerchantAlias` lookup, no hidden Business-A-specific logic. `MerchantAlias`
      just needs `business_id` like any other Phase 2 table.
- [ ] 0.8 **NEW** — Investigate AK PMU's deposit model against real Square data once its Square account
      is connected (Phase 7): how Square represents a deposit taken weeks before the appointment, and
      how it should net against the final visit total without double-counting revenue/commission —
      design.md Open Question 8. Do not design this blind; use the same live-data-first methodology as
      the P0 payment-accounting investigation (custom-amount lines, cash-note gaps) that preceded this.
      Gates AK PMU's onboarding (Phase 7), not the core Phase 1-3 tenant-boundary work.
- [x] 0.9 **NEW, already shipped ahead of this change** — the P0 payment-accounting investigation found
      and fixed two real revenue-visibility bugs in the shared reconciliation pipeline (Square
      "Custom Amount" / no-catalog-item line items were silently dropped; cash-note gaps were recorded
      as phantom salon discounts instead of being matched against real unattributed payments). Both are
      channel- and business-agnostic fixes already in production, serving Business A today and AK PMU
      for free once it's onboarded — directly resolves the "Card + Cash" / "Two Card Payments"
      reconciliation concerns AK PMU's own onboarding notes raised.

## Phase 1 — Domain/database foundation

- [ ] 1.1 `V84__business.sql` — `business(id BIGSERIAL PK, name TEXT NOT NULL, short_code TEXT UNIQUE
      NOT NULL, timezone TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), active BOOLEAN
      NOT NULL DEFAULT true)`; insert Business A's row (`short_code = 'akluxnails'`, derived from
      existing `salon_config.owner_short_name = 'AK'`)
- [ ] 1.2 `Business` JPA entity + `BusinessRepository`
- [ ] 1.3 `V85__business_membership.sql` — `business_membership(id, business_id FK, user_id FK
      app_user, created_at, UNIQUE(business_id, user_id))`; backfill one row per existing `app_user`
      pointing at Business A
- [ ] 1.4 `BusinessMembership` entity + repo; add `activeBusinessId` to `AppUserPrincipal`, resolved at
      login as the sole membership row (fail loudly, not silently, if a user somehow has 0 or >1 until
      the switcher exists)
- [ ] 1.5 `CurrentBusinessContext` request-scoped bean (design.md D7) + a servlet filter that populates
      it immediately after Spring Security authentication, before controller dispatch
- [ ] 1.6 `V86__salon_config_business_scope.sql` — drop `CHECK (id = 1)` on `salon_config`, add
      `business_id BIGINT UNIQUE NOT NULL REFERENCES business(id)` (1:1), backfill Business A's
      existing row
- [ ] 1.7 Delete `SalonConfigRepository.findById(Integer)`; add
      `findByBusinessId(Long)`; fix the resulting compile errors at all 13 call sites listed in
      design.md D6, one file per commit, each verified against the regression snapshot from Phase 8.1
- [ ] 1.8 Apply Hibernate `@FilterDef`/`@Filter("businessFilter")` to every entity classified
      "needs a direct business_id column" in the companion analysis document's Database Changes
      section; enable the filter from `CurrentBusinessContext` in the same servlet filter as 1.5
- [ ] 1.9 Backend unit tests: `CurrentBusinessContext` resolution, `BusinessMembership` backfill
      idempotency, `SalonConfigRepository` compile-time removal doesn't silently leave a call site
      unscoped (grep-based test asserting zero occurrences of `findById(1)`/`findById(Integer)` against
      `salon_config` in the compiled sources)

## Phase 2 — Tenant-aware core tables (payroll/commission path)

- [ ] 2.1 Add `business_id` to `providers`, `app_user` (composite unique
      `(business_id, username)` replacing global `username` unique), `pay_periods` (composite unique
      `(business_id, year, month, half)`), `revenue_snapshot` (composite unique
      `(business_id, snapshot_date)`)
- [ ] 2.2 Rewrite the highest-risk repository queries flagged in research: `AppUserRepository
      .findByUsername` → `findByBusinessIdAndUsername`; `PayPeriodRepository
      .findByYearAndMonthAndHalf` → business-scoped equivalent
- [ ] 2.3 Tables inheriting scope through FK (`period_entries`, `tier_grant`, `prepaid_package`/
      `prepaid_redemption`, `redo`, `manual_adjustment`, `no_show_fee_override`, `settlement_feedback`,
      `provider_visit`, `provider_square_member`, `manager_pay_rate`, `manager_time_entry`) — verified
      via join-based Hibernate filter inheritance (`@Filter` cascades through the `provider`/`app_user`
      association), no new column added unless a query pattern needs the denormalized column for index
      performance (assess per-table during implementation)
- [ ] 2.4 `owner_customer`, `suspicious_booking_clearance`, `cancellation_clearance`,
      `suspicious_triage` — no existing FK path to business (keyed only by Square IDs); add
      `business_id` directly per design's "ambiguous, needs a bolted-on column" classification
- [ ] 2.5 Cross-tenant isolation integration test suite (new): stand up Business A + a synthetic
      Business B fixture in the test DB; assert every settlement/report/user/provider endpoint scoped
      to A never returns a B row and vice versa
- [ ] 2.6 **NEW, found 2026-08-15 as a live incident** — same "no FK path, needs a bolted-on
      `business_id` column" gap as 2.4, but never inventoried because these tables predate any
      multi-tenant work: `sms_message`, `twilio_sms_config`, `telegram_config`, `kb_articles`,
      `kb_request`, `rag_document`, `rag_chunk`, `rag_agent_config`, `rag_suggestion_cache`,
      `rag_redaction_audit`, `sops`, `sop_versions`, `sop_acknowledgments`, `staff_documents` all have
      zero tenant boundary. Confirmed exploitable: the moment AK PMU's OWNER account existed, it could
      read Business A's SMS conversations and live Twilio/Telegram credentials — reported live by the
      owner and fixed same-day for those three specifically (`SmsBusinessScopeFilter`, PR #370): those
      paths now 403 for any business but Business A, the same stopgap shape as
      `BusinessRepository#legacySmsBusiness`. **KB articles, RAG documents/chunks, SOPs, and staff
      documents were deliberately left unfixed** — blocking them the same way would leave a real
      second business unable to use its own KB/SOPs/documents at all, which is a product decision, not
      a security one; scope was judged non-critical while there are only two businesses and this is
      tracked here instead of patched under pressure. **Hard gate: this task must be fully closed
      (real `business_id` columns + filtered queries + the cross-tenant isolation suite in 2.5
      extended to cover these tables) before Phase 7 runs for a third business** — the stopgap
      approach (allow-list one hardcoded business, 403 everyone else) does not scale past two
      businesses and must not be repeated as a shortcut when business 3 is onboarded.

## Phase 3 — Square multi-account support

- [ ] 3.1 `V87__square_connection.sql` — `square_connection(id, business_id UNIQUE FK, environment,
      access_token_encrypted, location_id, merchant_id NULLABLE, connected_by_user_id, connected_at,
      last_sync_at)`
- [ ] 3.2 AES-GCM encryption helper for `access_token_encrypted`, master key from a new env var
      (delivered via the `docker-swarm-secrets` proposal's mechanism if that change has landed by
      then, plain env var otherwise — coordinate with that change, don't block on it)
- [ ] 3.3 Manual one-time backfill script (documented, not a Flyway migration — involves a live
      secret): move `SQUARE_ACCESS_TOKEN`/`SQUARE_LOCATION_ID` into Business A's `square_connection`
      row
- [ ] 3.4 `SquareClientProvider` — replaces the `@Component SquareClient` singleton with a
      registry/factory keyed by `businessId`, short-TTL-caching constructed `SquareClient` instances;
      `SquareClient`'s internals (cache map, `Semaphore(6)`, TTLs, `invalidate()`) are unchanged —
      correctness for multi-tenant falls out of "one instance per business" per design.md D5
- [ ] 3.5 Update all ~10 call sites that inject `SquareClient` directly (`ManagerTimeService`,
      `NoShowFeeService`, `PrepaidService`, `ProviderVisitScheduler`, `ProviderVisitIngestService`,
      `RevenuePulseService`, `RetentionAnalyticsService`, `RevenueSnapshotService`,
      `RevenueSnapshotScheduler`, `SquareMonthAggregator`, `UserController`) to resolve via
      `SquareClientProvider.forBusiness(currentBusinessContext.id())`
- [ ] 3.6 `SquareWebhookController` — move from one global HMAC key to per-business signature
      key/notification URL; route `POST /api/public/webhooks/square/{businessId}` (path-based,
      verified before trusting any payload field, per design's rejection of trusting the unauthenticated
      `merchant_id` field pre-verification)
- [ ] 3.7 `ProviderVisitScheduler`, `RevenueSnapshotScheduler`, their `*Startup` counterparts, and
      D9's six SMS automation schedulers (`RepeatCustomerWinbackScheduler`,
      `LapsedCustomerWinbackScheduler`, `LeadFollowUpScheduler`, `SameDayRebookingScheduler`,
      `SameDayRebookingGroupExpiryScheduler`, `SmsReplyFlowScheduler`) plus
      `CheckoutReviewTriggerService`, `TechnicianNameResolver`, and `InternalNotificationController` —
      iterate all businesses with an active `square_connection` (SMS ones: also an active
      `twilio_sms_config`, once that's business-scoped per 2.6); ShedLock keys gain `-business-{id}`
      suffix (`config/SchedulerLockConfig.java`). **Interim stopgap shipped 2026-08-15**: the SMS/
      webhook/notification call sites now resolve `BusinessRepository#legacySmsBusiness` (hardcoded to
      Business A) instead of crashing when a second business exists — correct only because `sms_message`/
      `twilio_sms_config` are still global (2.6) so there's no second business's SMS data to route to
      yet regardless. This does not scale to a third business's SMS needs and must be replaced by real
      per-business iteration here, not extended with a second hardcoded business id.
- [ ] 3.8 `/api/sync` (manual sync button) becomes business-scoped — `invalidate()` only clears the
      calling business's `SquareClient` cache instance, never the whole registry
- [ ] 3.9 Integration tests: two businesses' `SquareClientProvider`-resolved clients never share cache
      state or throttle semaphores; webhook routing test with two businesses' signature keys

## Phase 4 — Business-specific financial configuration

- [ ] 4.1 (Gated on Phase 0.1) If Business B's rule is config-only: enter its values into its
      `salon_config` row during onboarding, verify against the worked example from 0.1, done — no
      code change
- [ ] 4.2 (Gated on Phase 0.1) If Business B's rule is structural: extract `CommissionEngine`
      interface per design.md D10, `TierCommissionEngine implements CommissionEngine` with no behavior
      change (verified against Phase 8.1's regression snapshot), add `BusinessBCommissionEngine` (name
      TBD), add `commission_engine` discriminator to `salon_config`, factory resolution in
      `SettlementPreviewService`
- [ ] 4.3 `V88__business_feature.sql` — `business_feature(business_id, feature_key, enabled,
      UNIQUE(business_id, feature_key))`; migrate existing global boolean flags (`rag.enabled`,
      `ai.triage.enabled`, `ai.funnel-analysis.enabled`, `ai.sms-draft.enabled`,
      `rag.suggestions.enabled`) to per-business rows, defaulting Business A to its current values and
      Business B to all-off except commission/Square/settlements (core, unconditional)
- [ ] 4.4 (Gated on Phase 0.5) Promote `NoShowFeeService`'s hardcoded `$25.00` (`NoShowFeeService.java:50`)
      to a nullable `salon_config`/business-setting field; Business A keeps $25 as its value, Business B
      gets its own value or null (feature off) per 0.5's answer
- [ ] 4.5 (Gated on Phase 0.6) Make `CashNoteParser`'s bilingual keyword matching
      (`square/CashNoteParser.java:28-29`) business-scoped: at minimum an on/off flag per business, or a
      configurable keyword list if Business B needs a different convention, per 0.6's answer

## Phase 5 — Authentication/authorization

- [ ] 5.1 `OwnerBootstrap` becomes business-creation-time seeding (new `POST /api/platform/businesses`
      creates a `business` row + seeds its first OWNER), replacing the current single-shot
      app-startup `ApplicationRunner` — old env-var-based bootstrap kept only as the one-time path for
      re-seeding Business A during migration
- [ ] 5.2 Narrow `platform_admin` table + check (design.md D4) — no new `Role` enum value; a small
      number of new `/api/platform/*` endpoints (`GET /businesses`, `POST /businesses`,
      `POST /businesses/{id}/suspend`) gated on this flag, reviewed for accidental broad grants
- [ ] 5.3 `UserController` (owner-only) — creation/edit scoped to `currentBusinessContext.id()`;
      Square-roster lookup (`squareRoster()`) resolves via `SquareClientProvider` (depends on Phase 3)
- [ ] 5.4 Security/authorization tests: an OWNER of Business A calling any `/api/users/**`,
      `/api/providers/**`, `/api/settlements/**` path with a Business-B-owned resource id gets 404, not
      200-with-wrong-data and not 403-leaking-existence

## Phase 6 — UI/business context

- [ ] 6.1 `businessId` cookie added alongside `sid`/`role` (design.md D12), set at login, refreshed
      per proxied call in `proxyBackend.ts` the same way `role` already is
- [ ] 6.2 `AdminMenu.tsx` — business-context row: plain text for single-membership users (no visual
      change from today), `<select>` + new `/api/business/switch` proxy route only when a user has
      >1 membership row (contingent on Phase 0.3's answer — may ship schema-only with UI deferred)
- [ ] 6.3 `app/owner/marketing/period.ts:28`'s hardcoded `SALON_TIME_ZONE` constant replaced with the
      backend-supplied business timezone, matching the existing pattern already used correctly by
      `report.timezone`/`detail.timezone` elsewhere in the frontend
- [ ] 6.4 New `/onboarding` flow (OWNER-only, platform-admin-created business's first login): connect
      Square (paste personal access token + location id → `POST /api/square/connection`), invite
      first MANAGER/PROVIDER users — mirrors today's manual setup process, just moved into the product
      instead of being a deploy-time step
- [ ] 6.5 Playwright e2e: business-switcher renders correctly for both 1-membership and 2-membership
      fixtures; onboarding flow end-to-end against Square sandbox credentials

## Phase 6b — Onboarding UX: graceful "not set up yet" states

**Found live 2026-08-15 onboarding AK PMU**: a freshly created business hits raw errors on every page
that needs a setup step it hasn't finished yet (a stack-trace-shaped 500 on `/reports` before Square
is connected, a 403 with no explanation on `/admin/messages`) instead of being told what to do.
Backend infra shipped same day: `BusinessSetupIncompleteException` (thrown by the low-level service
that hit the gap, e.g. `SquareClientProvider`) + `GlobalExceptionHandler` turns it into a 409 with a
stable `code` field; `SmsBusinessScopeFilter` returns the same `{code, message}` shape on its 403.
Frontend: `ApiError` (serverApi.ts) surfaces `status`/`code` from any failed `serverFetch` call without
changing behavior for existing callers; `SetupRequiredNotice` is the reusable empty-state component.

- [x] 6b.1 `/reports` — catches `square_not_connected`, renders `SetupRequiredNotice` with a CTA to
      `/owner/settings/square` instead of the raw 500
- [x] 6b.2 `/admin/messages` — catches `sms_not_available`, renders `SetupRequiredNotice` (no CTA —
      there's nothing to configure yet, see 2.6) instead of the raw 403
- [ ] 6b.3 **Not yet covered — same treatment needed wherever else a fresh business can hit a missing
      Square connection or missing config**: `/owner/overview` and its sub-pages (revenue pulse, net,
      expenses), `/owner/marketing/**`, `/owner/retention`, `/admin/redos`, `/admin/prepaid`,
      `/admin/owner-customers`, `/admin/manual-adjustments`, anything under `/reports/[providerId]/**`
      — audit each for what it actually throws today (some may already go through
      `BusinessSetupIncompleteException` for free once `SquareClientProvider`/`ProviderDirectory`
      callers are consistent; others may need a new `code`) before assuming this is done
- [ ] 6b.4 RAG assistant, Telegram settings, KB articles, SOPs — once 2.6 gives these real
      per-business scoping (rather than the current Business-A-only block), they'll need this same
      "not set up yet" treatment too, not just a bare 403/500, the first time a second business
      actually gets access to them

## Phase 7 — Second salon (Business B / AK PMU) onboarding

- [x] 7.1 Create Business B's `business` row, connect its Square production credentials — shipped
      2026-08-15 via `POST /api/platform/businesses` + `/owner/settings/square` (PR #368), not the
      originally-planned Phase 6.4 `/onboarding` flow, which was folded into the Business Settings
      admin UI instead
- [x] 7.2 Enter Business B's `salon_config` values — 45%/55% commission, tier bonus off, 3.5% card tip
      fee, via `/owner/settings/business` (PR #368)
- [ ] 7.3 Invite Business B's ~2 providers as `app_user` rows with `PROVIDER` role — not yet done
- [x] 7.4 Business_feature-style gating isn't built (2.6/4.3 still open), so this landed as a stricter
      "off entirely, not configurable per-business yet" default: SMS/Telegram/KB/RAG/SOPs/staff-docs
      all 403 or are otherwise unusable for Business B until 2.6 closes — see that task for why this
      was judged acceptable for exactly two businesses but not a pattern to repeat
- [ ] 7.5 Shadow-run for one real pay period: compare salaryReview's computed settlement against
      Business B's current manual process before treating it as authoritative

## Phase 7b — Third+ business onboarding: hard gate

Business B (AK PMU) shipped 2026-08-15 with several deliberate, hardcoded-to-"exactly one other
business" stopgaps (2.6, 3.7's interim note) instead of the real per-business generalization Phase 3
originally scoped — judged acceptable only because there were exactly two businesses and the
alternative was rushing a bigger change under live-incident pressure. None of that reasoning holds for
a third business: a hardcoded `legacySmsBusiness()`/`SmsBusinessScopeFilter` allow-list has no way to
also allow-list a second non-Business-A business without becoming exactly the kind of ad-hoc,
un-reviewed patch this file exists to prevent.

- [ ] 7b.1 **Do not onboard a third business until 2.6 and 3.7 are both fully closed** — real
      `business_id` columns (not a hardcoded business allow-list) on every table listed in 2.6, real
      per-business iteration (not `legacySmsBusiness()`) on every call site listed in 3.7, and 2.5's
      cross-tenant isolation suite extended to cover all of it
- [ ] 7b.2 The point of 7b.1: onboarding a third (or Nth) business should need zero new backend/
      frontend code by then — just a `business` row + owner account through the existing platform-admin
      flow (Phase 5.1/6.4), same as any business after the second. If a third business turns out to
      still need a code change, that's a signal 2.6/3.7 weren't actually finished — fix the gap there,
      don't add a third hardcoded business id next to the other two

## Phase 8 — Regression and security testing

- [ ] 8.1 **Before Phase 1 starts**: capture Business A's last 6 closed months' `/api/settlements/preview`
      output per provider/month and `/owner/overview` net-profit figures as a golden snapshot
- [ ] 8.2 **After each phase**: re-run the same requests against the migrated schema, assert
      byte-for-byte equal `BigDecimal` output; any diff blocks merge
- [ ] 8.3 Full cross-tenant isolation suite (Phase 2.5, 3.9, 5.4) green
- [ ] 8.4 RAG vector-search cross-tenant test: Business A's uploaded documents never surface in
      Business B's chat-assistant results and vice versa (design.md's flagged highest-severity finding)
- [ ] 8.5 `security-review` skill run against the full diff before merge to master

## Phase 9 — Production rollout

- [ ] 9.1 Deploy migrations `V84`+ to production during a maintenance window; run Phase 8.1/8.2's
      snapshot comparison against production data immediately after
- [ ] 9.2 Confirm existing scheduled jobs (SMS automations, revenue snapshots) still fire correctly
      for Business A post-migration before onboarding Business B
- [ ] 9.3 Onboard Business B (Phase 7) only after 9.1/9.2 have run clean in production for at least one
      full day/night cycle
- [ ] 9.4 Update `docs/DEPLOY.md`, `docs/ROADMAP.md`, and `openspec/config.yaml`'s stale
      "next migration is V18" context note

## Rollback strategy (all phases)

Every migration in Phases 1–4 is additive (new tables, new nullable-then-backfilled columns) until the
specific PR that removes the old singleton/global-unique constraint in the same change as the code
that stops relying on it — so any single PR can be reverted independently without leaving the schema in
a broken intermediate state. The `salon_config` `findById(1)` removal (1.7) is the one genuinely
all-or-nothing step per file; each of its 13 call sites is its own small commit specifically so a bad
one can be reverted without reverting the whole migration.
