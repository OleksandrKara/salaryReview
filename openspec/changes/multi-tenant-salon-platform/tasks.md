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
      multi-tenant work: `sms_message`, `twilio_sms_config`, `telegram_notification_config`,
      `kb_articles`, `kb_request`, `rag_document`, `rag_chunk`, `rag_agent_config`,
      `rag_suggestion_cache`, `rag_redaction_audit`, `sops`, `sop_versions`, `sop_acknowledgments`,
      `staff_documents` all have zero tenant boundary. Confirmed exploitable: the moment AK PMU's
      OWNER account existed, it could read Business A's SMS conversations and live Twilio/Telegram
      credentials — reported live by the owner and fixed same-day for those three specifically
      (`SmsBusinessScopeFilter`, PR #370): those paths now 403 for any business but Business A, the
      same stopgap shape as `BusinessRepository#legacySmsBusiness`. **`twilio_sms_config` and
      `telegram_notification_config` are now genuinely business-scoped** (V95/V96 — real `id BIGINT
      IDENTITY` PK + `business_id BIGINT UNIQUE NOT NULL FK`, replacing the boolean-singleton PK;
      `TwilioSmsSettingsController`/`TelegramSettingsController` resolve via
      `CurrentBusinessContext`, verified in an isolated environment restored from a real backup). The
      `SmsBusinessScopeFilter` 403 stopgap is left in place for now — the ~15 automation call sites
      (schedulers, webhooks) still resolve via `legacySmsBusiness()` rather than real per-business
      iteration (tracked in 3.7), so a second business's config, even though storable, isn't reachable
      by automation yet. `sms_message` itself is still unscoped (still behind the filter, no
      `business_id` column). **`staff_documents` is now genuinely business-scoped** (PR #377 — no
      migration needed: `provider_id`/`app_user_id` are real FKs into already business-scoped
      `Provider`/`AppUser`, so `StaffDocumentRepository` filters via a join through those, same idiom
      as `RedoRepository`/`ManualAdjustmentRepository`; `StaffDocumentController`'s owner-side
      list/create/update/delete/download resolve via `CurrentBusinessContext`). **`sops` (and by
      extension `sop_versions`/`sop_acknowledgments`) is now genuinely business-scoped** too — `sops`
      got a real migration (`business_id BIGINT UNIQUE`-less FK, since a business can have many SOPs
      unlike the singleton config tables), backfilled to Business A; `sop_versions`/
      `sop_acknowledgments` need no migration of their own — every access joins through an
      already-verified `sops.business_id` (same join idiom as `staff_documents`), since the service
      layer always resolves+verifies the parent `Sop` before touching a version or acknowledgment row.
      `SopController`/`SopSyncController` resolve via `CurrentBusinessContext`. **`kb_articles` and
      `kb_request` are now genuinely business-scoped** too (V98) — both are root tables with no
      existing FK into an already business-scoped table (unlike `staff_documents`/`sop_versions`), so
      both got a real `business_id BIGINT NOT NULL FK` migration, backfilled to Business A;
      `KbArticleController`/`KbRequestController` resolve via `CurrentBusinessContext`. KB→RAG sync
      (`KbSyncService`) is untouched beyond which articles get selected for syncing. **`rag_document`,
      `rag_agent_config`, `rag_redaction_audit`, and `rag_suggestion_cache` are now genuinely
      business-scoped** (V99-V102): `rag_document`/`rag_redaction_audit` are root tables (the latter
      intentionally has no FK to `rag_document` — it must survive a deleted document — so it needs its
      own `business_id` rather than a join); `rag_agent_config` keeps its `version` PK and global
      version-numbering counter unchanged but gained a `business_id` column and a re-scoped
      `(business_id, active) WHERE active` unique index (one active config per business, not
      globally); `rag_suggestion_cache`'s PK became composite `(business_id, language)` (was
      `language` alone — a real PK-shape change, `RagSuggestionCacheId`/`@IdClass`). `rag_chunk` gets
      no `business_id` column of its own — every access (including the native pgvector nearest-
      neighbour search in `RagChunkRepository#searchNearest`, the query the live chat assistant
      depends on) joins through `rag_document.business_id`. Every RAG service/controller call site
      that reaches these tables was updated: `RagIngestionService`, `RagRetrievalService`,
      `RagAnswerService`, `RagConfigService`, `RagSuggestionService`, `RagAdminController`,
      `RagController`, plus two non-obvious call sites found by grepping the whole tree —
      `KbSyncService`/`SopSyncService` (already business-scoped from earlier in 2.6, now thread
      `businessId` into their `RagIngestionService` calls too) and `SmsDraftService` (the SMS
      "Generate" button's RAG-grounding lookup, called from `SmsActivityController`, an
      `/api/owner/automations/**` path — only reachable by Business A today via
      `SmsBusinessScopeFilter`, but resolved via `CurrentBusinessContext` for correctness regardless).
      Verified via `CrossTenantIsolationTest` against a real Postgres, including a vector-search test
      that gives Business B's chunk the objectively nearest embedding to prove a Business A query
      still never returns it. **`sms_message` and `sms_reply_flow` are now genuinely business-scoped
      at the data layer** (V103) — both are root tables with no existing FK into an already
      business-scoped table (`sms_reply_flow.automation_key` FKs into `sms_automation`, which stays a
      global registry of automation *types*, deliberately not scoped), so both got a real
      `business_id BIGINT NOT NULL FK` migration, backfilled to Business A (every existing row already
      belonged to A regardless, since `SmsBusinessScopeFilter` had blocked any other business from
      writing to these paths). `sms_message_media`/`sms_message_reaction` (V69/V70) need no migration
      of their own — both join through an already-verified `sms_message.id`. Every consumer was
      updated to take/resolve a `businessId`: `SmsMessageRepository`'s ~25 methods (including the
      native `conversationSummaries`/`conversationSummariesPage`/`conversationSummaryForPhone`
      queries), `SmsMessageLogService` (the central read/write choke point), `SmsReplyFlowRepository`,
      `SmsReactionService`, `SmsAutomationService.list`, `TwilioSmsService` (all three send methods —
      `configService.getForAutomation()` calls became `configService.get(businessId)`),
      `CheckoutReviewReplyService` (derives `businessId` from the `SmsReplyFlow` row itself, not a
      separate parameter), `CheckoutReviewFlowRecoveryService.retry` (now verifies flow ownership via
      `findByIdAndBusinessId` before acting), `SmsActivityController`/`SmsAutomationController`/
      `SmsReplyFlowAdminController` (resolve via `CurrentBusinessContext`), and every background/
      webhook call site with no session — `TwilioInboundSmsController`, `SmsReplyFlowScheduler`,
      `CheckoutReviewTriggerService`, `InternalNotificationController`, and the four D9 SMS
      schedulers (`LapsedCustomerWinbackScheduler`, `RepeatCustomerWinbackScheduler`,
      `SameDayRebookingScheduler`, `LeadFollowUpScheduler`) — which **deliberately still resolve
      `BusinessRepository#legacySmsBusiness()`** rather than getting real per-business iteration; that
      remains 3.7's job, done separately from this data-layer change, per this task's own established
      pattern for `twilio_sms_config`/`telegram_notification_config`. `ShortLinkController` (the
      public `/r/{token}` redirect) and `SmsMessageLogService#updateDeliveryStatus`/
      `#markRead`-by-token-style lookups (`findByClickToken`, `findByTwilioMessageSid`,
      `existsByClickToken`) deliberately stay unscoped — both tokens are globally unique and
      self-identifying, and the row they resolve to already carries its own `business_id`, so there's
      no external business hint to filter by (same reasoning `RagIngestionService.approve/delete`
      already established: derive correctness from the row, not an injected hint). `CrossTenantIsolationTest`
      coverage added for `conversationSummaries`, `search`, and `SmsReplyFlowRepository`'s due-send/
      reply-lookup queries — **written but not run against a real Postgres from this implementation
      pass** (the sanctioned local test-DB container was unavailable — `docker.sock` was back at its
      restricted 660 permission mid-task; every other file in this task's diff was compiler-verified
      and, for the ~10 already-existing test files touched, follows the exact same mechanical
      rename pattern proven correct in every prior chunk of 2.6 tonight — but this specific new test
      needs a real-DB run before shipping, not just a compile check, per the lesson from PR #379's
      test-fixture bug). **This closes every table in 2.6's original list.** Nothing in this task
      implements real per-business scheduler iteration — the `SmsBusinessScopeFilter` 403 stopgap for
      non-Business-A also stays in place; both remain 3.7's explicit follow-up. **Hard gate: this task
      is now fully closed (real `business_id` columns + filtered queries for every table in the
      original list, cross-tenant isolation coverage for all of them pending the real-DB test run
      above) — Phase 7 (onboarding a third business) still additionally requires 3.7's real
      per-business scheduler iteration to actually ship**, not just this task, since a third business
      with its own `twilio_sms_config` row would otherwise have no scheduler ever reading it.

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
- [x] 3.6 **Shipped 2026-08-17.** `SquareWebhookController` now has two routes, two key sources.
      `POST /api/public/webhooks/square` is Business A's original, already-configured-in-Square's-
      dashboard subscription — left completely unchanged (global `SquareWebhookProperties` key,
      resolves `legacySmsBusiness()`) so its real production webhook keeps working with zero
      disruption; no dashboard change needed for Business A. `POST
      /api/public/webhooks/square/{businessId}` (path-based) is the real per-business route for
      every other business: `square_connection` gained a nullable `webhook_signature_key_encrypted`
      column (V106, same `SquareCredentialCipher` as `access_token_encrypted`, no SQL backfill —
      encryption needs the app's own master key), and the new route only ever accepts a request
      signed with *that* business's own key, verified *before* trusting anything in the payload
      (rejects Business A's/the global key, or any other business's key, on a mismatched path —
      covered by two explicit security tests, `perBusinessRouteRejectsWrongBusinessSignature` and
      `perBusinessRouteRejectsAnotherBusinessOwnSignature`). A business with no key configured yet
      404s (nothing set up) rather than 401 (wrong key) — a deliberately different signal for
      debugging, also covered by its own test. `SquareConnectionController`'s
      `GET/PUT /api/owner/settings/square` gained `webhookSignatureKeyMasked`/`webhookSignatureKeySet`
      (same null/blank-keeps-existing convention as `accessToken`) and a read-only, computed
      `webhookNotificationUrl` field — the exact URL (byte-for-byte, since it's part of the HMAC
      input Square signs) an owner needs to paste into their business's Square Developer Dashboard
      webhook subscription. `CheckoutReviewTriggerService.handlePaymentUpdated` now takes
      `businessId` as a parameter instead of resolving `legacySmsBusiness()` internally — the
      legacy route passes its `legacySmsBusiness()`-resolved id, the new route passes the real
      per-business id from the URL, so the service itself has no opinion on how that resolution
      happened. `TechnicianNameResolver.resolveForCustomer` also gained a `businessId` parameter,
      threaded from `SmsReplyFlowScheduler`/`SameDayRebookingScheduler`'s own already-real
      per-business loop variable (found while doing this — those two callers already had a real
      businessId in scope since PR #382/#383, but the resolver itself was still silently querying
      only Business A's Square bookings for technician-name lookups). Verified against a real,
      completely fresh local Postgres: 978 tests, 0 failures, 0 errors, including all 8
      `SquareWebhookControllerTest` scenarios (4 legacy-route, 4 per-business-route). **The code is
      ready; a second business's own external configuration is not** — AK PMU (or any future
      business) needs a real webhook subscription created in *their own* Square Developer
      Dashboard (event: `payment.updated`, URL: their business's `webhookNotificationUrl` from the
      settings GET response), with the resulting signing key pasted into
      `/owner/settings/square` before `checkout_review_request`/`same_day_rebooking_discount`
      actually start firing for them — that's a real action for the business owner to take, not a
      remaining code gap.
- [ ] 3.7 **Not applicable to the RAG services** (`RagIngestionService`, `RagRetrievalService`,
      `RagAnswerService`, `RagConfigService`, `RagSuggestionService`) — none of them run as an
      `@Scheduled` background job; every call site is synchronous and session-triggered (an owner
      clicking sync/approve/ask, or the SMS draft "Generate" button), so each already resolves a real
      `businessId` via `CurrentBusinessContext` with no `legacySmsBusiness()`-shaped stopgap needed
      (2.6). `ProviderVisitScheduler`, `RevenueSnapshotScheduler`, and their `*Startup` counterparts
      already do real per-business iteration over `SquareConnectionRepository.findAll()` — no
      `legacySmsBusiness()`/`.sole()` call sites remain in that path.

      **2026-08-16: real iteration shipped for the part of D9 that's actually safe to iterate.**
      `sms_message`/`sms_reply_flow` becoming genuinely business-scoped (2.6) plus `sms_automation`
      becoming business-scoped this same day (composite PK `(business_id, automation_key)`, same
      surgery as `rag_suggestion_cache`'s V102) unblocked `SmsReplyFlowScheduler`
      (`sendDueRatingRequests`/`expireStaleReplyWindows`) to really iterate every business with a
      `twilio_sms_config` row (`TwilioSmsConfigRepository.findAll()`), and `TwilioInboundSmsController`
      to resolve the real business from Twilio's own `To` field (matched against
      `twilio_sms_config.from_phone_number`, falling back to `legacySmsBusiness()` + a warning log
      only for an unrecognized destination number). `SmsBusinessScopeFilter` — the 2026-08-15
      live-incident stopgap that blocked every business but A from `/api/owner/automations/**`,
      `/api/owner/settings/sms/**`, `/api/owner/settings/telegram/**` entirely — is now removed;
      every controller behind those paths resolves via `CurrentBusinessContext` like everything
      else, so the blanket block was no longer earning its keep. ShedLock names were deliberately
      **not** given a `-business-{id}` suffix — a single lock still covers the whole per-business
      loop, which is still correct (no duplicate sends across blue/green), just not maximally
      parallel across businesses; revisit only if that actually matters at a higher business count.

      **Still NOT real, found 2026-08-16 while doing the above — five of D9's six schedulers
      (`SameDayRebookingScheduler`, `SameDayRebookingGroupExpiryScheduler`, `LeadFollowUpScheduler`,
      `RepeatCustomerWinbackScheduler`, `LapsedCustomerWinbackScheduler`) still resolve
      `legacySmsBusiness()` and were deliberately left that way**, not because it wasn't tried, but
      because their own supporting tables have zero tenant boundary and making the top-level
      scheduler "iterate" without first fixing this would trade "never processes business 2" for
      "processes business 2 against business 1's own rows" — a real double-send/cross-tenant
      correctness bug, not just a missing feature. Specifically:
        - `same_day_rebooking_send` + `same_day_rebooking_group_membership` (V55) and
          `repeat_customer_winback_send` (V72) and `lapsed_customer_winback_send` (V68) have no
          `business_id` column at all — they're keyed only by `square_customer_id`, a raw string
          with no FK to anything. These need the same "root table, add `business_id`, backfill to
          Business A" treatment as every other 2.6 table before their schedulers can safely iterate.
        - `LeadFollowUpScheduler` additionally reads `marketing.contacts`, owned by a completely
          separate service (salonLandings) — not fixable from this codebase at all; that boundary
          needs its own cross-service design, not a migration here.
      `CheckoutReviewTriggerService` and `TechnicianNameResolver` (Square-payment-webhook-driven)
      remain on `legacySmsBusiness()` too — blocked on Phase 3.6, which doesn't exist yet:
      `SquareWebhookController` currently verifies every business's webhook against one single
      global `SQUARE_WEBHOOK_SIGNATURE_KEY`, not a per-business one, so even signature verification
      isn't multi-tenant-correct yet, before getting anywhere near routing. `InternalNotificationController`
      also stays as-is — it's a service-to-service API called by other apps (mani-backend,
      akluxnails-home) over a shared API key with no session; making it business-aware needs an API
      contract change on the *caller* side, not a decision to make unilaterally in this codebase.

      **2026-08-16 (later the same day): `same_day_rebooking_send`, `same_day_rebooking_group_membership`,
      `repeat_customer_winback_send`, `lapsed_customer_winback_send` given the `business_id`
      treatment** (V105 — root tables, standard `ADD COLUMN` + backfill-to-Business-A + `NOT NULL` +
      FK + index, same shape as every 2.6 table). `SameDayRebookingScheduler`,
      `SameDayRebookingGroupExpiryScheduler`, `RepeatCustomerWinbackScheduler`,
      `LapsedCustomerWinbackScheduler` now really iterate `TwilioSmsConfigRepository.findAll()`,
      same per-business try/catch resilience pattern as `RevenueSnapshotStartup` (one business's
      broken Square connection logs a warning and is skipped, doesn't abort the tick for the rest).
      `LapsedCustomerWinbackEligibilityRepository`/`RepeatCustomerWinbackEligibilityRepository`
      (plain-`JdbcTemplate`, read `provider_visit`) and `RepeatCustomerWinbackSendRepository
      .countConvertedSince` all gained a `businessId` filter. `SameDayRebookingTriggerService.enqueue`
      now takes `businessId` (passed through from `CheckoutReviewTriggerService`'s own resolved
      value — the *service* is business-id-correct even though its only caller still resolves
      `legacySmsBusiness()` until Phase 3.6 lands). `InternalNotificationController`'s
      `/rebooking-promo/enroll` now stamps the membership row it writes with the same
      `legacySmsBusiness()` id it already resolves for its Square call — no other change, still
      out of scope per the note above. `LeadFollowUpScheduler` (marketing.contacts, a separate
      service) and `CheckoutReviewTriggerService`/`TechnicianNameResolver` (Phase 3.6) remain
      exactly as described above — genuinely not fixable from this codebase alone.

      **Net effect for the owner-facing question "if I configure Twilio for a second business, do
      the same automations run?" — corrected/refined here, since this chunk's work exposed a
      nuance the PR #382 answer glossed over**: "will it run" and "is the table/scheduler ready"
      are two different questions, and checkout-review-request and same_day_rebooking-discount
      both fall on the wrong side of that split despite their scheduler/table layer being fully
      real now. repeat_customer_winback and lapsed_customer_winback — **yes**, fully functional
      the moment Twilio is configured: their schedulers are pure `@Scheduled` sweeps with no
      webhook dependency. checkout_review_request and same_day_rebooking_discount — **data/
      scheduler-correct but still not reachable in practice**: both are only ever triggered by the
      same Square payment webhook (`CheckoutReviewTriggerService.handlePaymentUpdated`, which also
      calls `SameDayRebookingTriggerService.enqueue`), and that webhook is still Phase-3.6-blocked
      (one global signature key, `legacySmsBusiness()` throughout) — so no second business's
      payment ever creates a row for either automation yet, regardless of how ready
      `SmsReplyFlowScheduler`/`SameDayRebookingScheduler` are to process one once it exists.
      four_hand_request (via `InternalNotificationController`) — no, needs a cross-app API
      contract change. lead_follow_up — no, blocked on a separate service's (salonLandings) own
      schema. **Bottom line at the time: every D9 automation was blocked on exactly one of two
      remaining gaps — Phase 3.6 (checkout_review_request, same_day_rebooking_discount) or a
      cross-codebase contract change (four_hand_request, lead_follow_up) — not on anything left to
      do inside this codebase's own schedulers/tables.**

      **2026-08-17: Phase 3.6 shipped** (see its own task entry above for the full detail).
      checkout_review_request and same_day_rebooking_discount are no longer blocked at the code
      level — `CheckoutReviewTriggerService`/`SameDayRebookingTriggerService` are genuinely
      business-id-correct now, real per-business Square webhook signature verification exists.
      **Revised bottom line: every D9 automation's code-level gap inside this codebase is now
      closed.** What's left for a second business to actually get every automation is entirely
      external to this codebase: AK PMU needs its own Square Developer Dashboard webhook
      subscription configured (Phase 3.6's own entry has the exact steps) for
      checkout_review_request/same_day_rebooking_discount, and four_hand_request/lead_follow_up
      still need the two cross-codebase contract changes described above — neither is a remaining
      backend task in *this* repository.
- [x] 3.8 **Shipped 2026-08-17.** `SquareSyncController`'s `SquareClient` invalidation was already
      per-business (`squareClientProvider.forBusiness(currentBusinessContext.id()).invalidate()`),
      but the same button's other 5 cache invalidations
      (`MarketingDashboardService`/`FunnelAnalyticsService`/`MarketingContactsService`
      /`MarketingAnalyticsService`/`OwnerOverviewService`) all called `TtlCache#invalidateAll()` —
      a global wipe — even though every one of them already keys its cached entries by
      `currentBusinessContext.id()`. One business's owner clicking "Sync now" was forcing every
      *other* business's already-fresh cache to also recompute on its next read (wasteful, not a
      data leak — reads were already correctly business-scoped). Added `TtlCache#invalidateWhere
      (Predicate<String>)`; each service's `invalidateCache()` now supplies its own key-format-
      aware matcher instead of clearing everything.
- [x] 3.9 **Shipped 2026-08-17, alongside 3.8.** Cache isolation: new `TtlCacheTest` covers
      `invalidateWhere` directly; new `MarketingContactsServiceTest
      #invalidateCacheOnlyDropsCallingBusinesssOwnEntry` proves business 1's `invalidateCache()`
      never evicts business 2's already-cached entry (same `TtlCache` instance, two businesses).
      Throttle-semaphore isolation was already structurally guaranteed (each business gets its own
      `SquareClient` instance — `squareCallPermits` is an instance field) and already covered by
      `SquareClientProviderTest`'s `assertThat(clientA).isNotSameAs(clientB)`. Webhook routing with
      two businesses' signature keys was already covered by 3.6's own
      `SquareWebhookControllerTest` (`perBusinessRouteRejectsWrongBusinessSignature`,
      `perBusinessRouteRejectsAnotherBusinessOwnSignature`).

## Phase 4 — Business-specific financial configuration

- [ ] 4.1 (Gated on Phase 0.1) If Business B's rule is config-only: enter its values into its
      `salon_config` row during onboarding, verify against the worked example from 0.1, done — no
      code change
- [ ] 4.2 (Gated on Phase 0.1) If Business B's rule is structural: extract `CommissionEngine`
      interface per design.md D10, `TierCommissionEngine implements CommissionEngine` with no behavior
      change (verified against Phase 8.1's regression snapshot), add `BusinessBCommissionEngine` (name
      TBD), add `commission_engine` discriminator to `salon_config`, factory resolution in
      `SettlementPreviewService`
- [x] 4.3 **Shipped 2026-08-18.** `V108__business_feature.sql` (numbered ahead of V88, sequenced
      after everything shipped so far, not the original slot) — `business_feature(id, business_id
      FK, feature_key, enabled, UNIQUE(business_id, feature_key))`. `BusinessFeatureService
      .isEnabled(businessId, key)` — a missing row means disabled, same ships-dark convention as
      the deployment-level flags themselves. Layered on top of (never replacing) each feature's
      existing `@ConfigurationProperties` flag at every call site: `RagController`/
      `RagAdminController` (`rag.enabled`, including the `suggestions`/`refreshSuggestions`/
      `askStream` endpoints, which had NO explicit gate before — only the class-level
      `@ConditionalOnProperty` — so this closes a real, previously-unguarded per-business gap, not
      just adds a second layer to an existing one), `RagSuggestionService` (`rag.suggestions
      .enabled`), `SuspiciousTriageController` (`ai.triage.enabled`), `FunnelAnalysisController`
      (`ai.funnel-analysis.enabled`), `SmsDraftService` (`ai.sms-draft.enabled`). `MeController`'s
      `features` block (`aiTriageEnabled`, `ragEnabled` — new field, `ragSuggestionsEnabled`) now
      reports the effective, business-scoped value; `ragFollowupsEnabled` deliberately stays
      deployment-only (not one of the 5 keys). Frontend: `AssistantWidget` previously had NO gate
      at all beyond role (OWNER/MANAGER) — it would render for every business and only fail once a
      question was actually asked; now hides entirely when `features.ragEnabled` is false. The AI
      triage Explain button, Funnel "Analyze" button, and SMS "Generate" button already had
      pre-existing catch/error-handling for a disabled backend, so no frontend change was needed
      for those three. Migration seeds Business A (id=1) `enabled=true` for all 5 keys, verified
      against the real production container's actual env (`RAG_ENABLED`/`AI_TRIAGE_ENABLED`/
      `AI_FUNNEL_ANALYSIS_ENABLED`/`AI_SMS_DRAFT_ENABLED`/`RAG_SUGGESTIONS_ENABLED` all `true`) —
      this migration changes nothing observable for Business A. Business B (AK PMU, id=2) gets no
      rows at all — this is the real, intentional behavior change: AK PMU loses the RAG assistant
      widget, AI triage Explain button, funnel analysis, and SMS draft suggestions it had never
      asked for and was silently getting anyway, until explicitly turned on for it. Verified via
      the full test suite (1037 tests, fresh Postgres, 0 failures) plus a live end-to-end check
      against an isolated instance restored from a real backup: `/api/me` for business 1's owner
      (platform_admin) shows all 4 business-scoped features true, business 2's owner shows all
      false; `/api/rag/suggestions` 200s for business 1 and 404s for business 2; `/api/suspicious/
      {id}/triage` 404s for business 2. `KbRequestController` (knowledge-gap requests, a separate
      feature that happens to share the `/api/rag` URL prefix) was deliberately left unscoped —
      not one of the 5 keys, and the frontend already swallows its 404/error into a harmless
      always-0 nav badge.
- [x] 4.4 **Shipped 2026-08-18.** `V109__no_show_fee_amount.sql` adds nullable `salon_config
      .no_show_fee_amount`; Business A backfilled to $25.00 (its historical hardcoded value, no
      observable change), every other business starts null. `NoShowFeeService.compute()` now
      resolves the caller's own configured amount and short-circuits to an empty result (no Square
      call at all) when null — a real "feature off" no-op, not just a detection change.
      `isCancellationFeeOrder` (also called from `SquareMonthAggregator`'s cancelled-appointments
      review — single source of truth for "was a fee already charged") now takes the amount as a
      parameter instead of a hardcoded ±$1 window around $25; null always returns false.
      `confirm()` now 400s when neither an explicit amount nor a configured business default
      exists — "no such thing as $0 by accident." Editable via the existing Business Settings form
      (`/owner/settings/business`), same "null = leave unchanged" convention as every other
      optional field there (no way to explicitly re-clear it back to null once set — an existing
      limitation shared by every other optional numeric field on that form, not new here).
      Verified: full suite (fresh Postgres, 0 failures) + a live end-to-end check against an
      isolated instance restored from a real backup — business 1's settings/no-show table
      unaffected, business 2's `/api/no-show-fees` 200s empty (no Square call), and a `confirm`
      attempt for business 2 gets a real 400.
- [ ] 4.5 (Gated on Phase 0.6) Make `CashNoteParser`'s bilingual keyword matching
      (`square/CashNoteParser.java:28-29`) business-scoped: at minimum an on/off flag per business, or a
      configurable keyword list if Business B needs a different convention, per 0.6's answer

## Phase 5 — Authentication/authorization

- [x] 5.1 Already shipped (PR #368, 2026-08-15) — `POST /api/platform/businesses`
      (`BusinessProvisioningService.create`) creates a `business` row + its first OWNER;
      `OwnerBootstrap`'s original env-var-based `ApplicationRunner` kept as-is, unconditional on
      `app_user` being empty, still the one-time path that seeded Business A originally.
- [x] 5.2 **Shipped 2026-08-18.** `platform_admin(user_id)` table (V107, no data seeded in the
      migration itself — see its own comment for why: Flyway runs before `OwnerBootstrap` ever
      creates the first app_user row, so a fresh environment's table would still be empty at that
      moment). `PlatformBusinessController`'s `GET`/`POST /api/platform/businesses` now both
      additionally require a `platform_admin` row for the caller (still `hasRole("OWNER")` at the
      URL level as a baseline) — before this, any business's own OWNER could list every business on
      the platform and create new ones with arbitrary owner credentials. Seeding: `OwnerBootstrap`'s
      fresh-instance path grants platform_admin to the very first OWNER it ever creates; a new,
      separate, idempotent `backfillPlatformAdmin` runner (same "safe on every boot forever" shape
      as `SquareConnectionBootstrap`) grants it to the existing `owner` account on an
      already-bootstrapped instance like production. `POST /businesses/{id}/suspend` from the
      original task text was never built at all (no code exists for it) — out of scope here, not a
      regression.
- [x] 5.3 Already correct before tonight — `UserController`/`squareRoster()` resolve entirely via
      `currentBusinessContext.id()`/`SquareClientProvider`, no `legacySmsBusiness()` stopgap left.
- [x] 5.4 **Shipped 2026-08-18 — writing the test found 3 real, live, exploitable cross-tenant
      vulnerabilities, not just a test gap.** `UserController` (`PATCH`/`DELETE /api/users/{id}`,
      plus `validateProviderLink`'s `providers.existsById`), `ProviderController` (`PATCH`/
      `DELETE /api/providers/{id}`), and `PayPeriodController` (`GET`/`DELETE /api/pay-periods/{id}`,
      `PUT .../entries/{providerId}`) all used plain, business-unscoped `findById`/`existsById`/
      `deleteById` — any business's OWNER could read, write, or delete **another business's real
      users, providers, and payroll data (procedures, card/cash totals, commission)** by id,
      constructing the request themselves (no UI would ever surface another business's ids, but
      nothing stopped a direct API call). Checked production data for signs of exploitation
      (app_user rows with a provider link outside their own business) — zero found; this was a
      live but never-actually-triggered gap. Fixed by swapping in each repository's existing (or,
      for `PayPeriodRepository`/`existsByIdAndBusinessId` on `ProviderRepository`, newly added)
      `findByIdAndBusinessId`-style method. New `ProviderControllerTest`/`PayPeriodControllerTest`
      (neither existed before) plus new cases in `UserControllerTest` cover both the rejection and
      the legitimate-same-business path for every affected endpoint. Confirmed each test actually
      fails against the pre-fix code (reverted, reran, restored) before shipping. `/api/settlements/
      **` (`SettlementController`/`SettlementPreviewController`/etc.) already resolved entirely via
      `currentBusinessContext.id()` — no separate id-based lookup to audit there.

## Phase 6 — UI/business context

- [x] 6.1 `POST /api/business/switch` (`BusinessSwitchController`) lets a platform_admin (or, for a
      future genuinely-multi-membership user, anyone with a real `business_membership` row for the
      target) change which business every subsequent request in the session acts on — sets a
      `CurrentBusinessContextFilter.ACTIVE_BUSINESS_SESSION_ATTR` session attribute, which the
      filter now checks before falling back to the login-time default (deliberately session state,
      not a mutable field on the {@code Serializable}, DB-session-backed `AppUserPrincipal` — see
      that class's own doc comment on the PR #351 `serialVersionUID` incident this avoids
      repeating). `writeMe`/`GET /api/me` both now report `activeBusinessId`
      (`CurrentBusinessContext`-sourced, switch-aware) and `businesses` (every active business for
      a platform_admin, else just the caller's own real membership(s)). Frontend: `businessId`
      browser cookie mirrors `role`'s handling exactly (set at login, refreshed on every proxied
      call via `proxyBackend.ts`, cleared on logout); `app/api/business/switch/route.ts` proxies
      the switch and updates the cookie's value from the response (#395). Verified end-to-end
      against a real, isolated instance restored from a real backup: login as platform_admin sets
      `businessId=1` → `/api/me` shows both businesses → switch to business 2 flips the cookie and
      `/api/me.activeBusinessId` → `/api/users` correctly scopes to business 2's own users only →
      switch back works → a non-admin's switch attempt to a business they don't belong to gets a
      real 403.
- [x] 6.2 `AdminMenu.tsx` dropdown: `/api/me`'s `businesses` array drives it — plain text (no
      visual change) for the ~100% single-membership case, a `<select>` calling the switch proxy
      route only when it has >1 entry. Every `PageHeader` call site that already pre-fetches `me`
      for role/language now threads `activeBusinessId`/`businesses` through too, so no extra
      `/api/me` round-trip was added to any existing page (#395). Verified: a single-membership
      user's rendered page has no dropdown; the platform_admin's does, with both business names.
- [x] 6.3 **Shipped 2026-08-18, mostly.** `period.ts`'s hardcoded `SALON_TIME_ZONE` constant is
      now `DEFAULT_TIME_ZONE`, an optional `timeZone` parameter on every function in the file
      (`todayIso`, `lastNWeeksRange`, `lastNMonthsRange`, `monthToDateSoFarRange`,
      `periodToBounds`), defaulting to the same Pacific value for any caller that doesn't pass one
      — so this shipped with zero required changes to any existing call site and zero observable
      behavior change (both real businesses are Pacific today). Threaded the real
      `BusinessSettingsDto.timezone` down from each tab's `page.tsx` (fetched via
      `serverApi.getBusinessSettings().catch(() => null)` — that endpoint is OWNER-only, fails
      open to the default for an ADS_MANAGER, same reasoning as the pre-existing
      `getSquareConnection()` catch already on the Overview tab) through to `PeriodFilter` for the
      **Overview, Contacts, and Funnel** tabs — `MarketingManager.tsx`, `ContactsFilterBar.tsx`
      (fixing a real bug found while doing this: `applyFilters` was a module-level function that
      would have referenced an out-of-scope `timeZone` if left as a bare closure — made it an
      explicit parameter instead), `FunnelView.tsx`.
      **Not done: the Ads Report tab** (`AdsReportView.tsx`) — its own module-level helpers
      (`thisWeekRange`, `thisMonthRange`, `isCurrentPeriod`) call `period.ts`'s functions with no
      `timeZone` threaded through yet; left alone rather than risk a mistake in the app's most
      complex revenue-reporting view for zero observable benefit tonight (still real Pacific-only
      behavior today, not broken). Verified: `tsc`/`build` clean; a live end-to-end check against
      an isolated instance restored from a real backup — Overview and Funnel both render with
      `timeZone: "America/Los_Angeles"` correctly threaded down to `MarketingManager`/`FunnelView`
      in the actual RSC payload; an ADS_MANAGER account gets the full dashboard with no error,
      correctly falling back to the default. (Contacts tab 500'd in this specific isolated
      env — traced to `Failed to decrypt Square credential` / `AEADBadTagException`, an
      environmental artifact of testing real encrypted prod data against a throwaway
      `SQUARE_CREDENTIALS_MASTER_KEY`, unrelated to this change — Overview/Funnel's success on the
      exact same env rules out a real regression.)
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
- [x] 6b.3 `/reports/[providerId]` (provider detail) — same `SettlementPreviewService` call path as
      `/reports`, confirmed to throw `square_not_connected` the same way; same treatment applied
- [x] 6b.3a **Audited, found already non-crashing — do NOT add dead try/catch code here**: verified
      directly against a real isolated instance (business with no Square connection) that
      `/api/owner/overview`, `/api/prepaid`, `/api/owner-customers`, `/api/redos`, and
      `/api/owner/marketing` all return **200 with empty/null data**, not an error — their services
      already degrade internally (return empty lists / all-null fields) rather than propagating
      `BusinessSetupIncompleteException`. A `try { ... } catch (ApiError code===square_not_connected)`
      was written for these pages first, then reverted once this was confirmed — it would never have
      fired, and shipping dead code that looks like it handles a case it doesn't is worse than not
      touching these pages at all. `/owner/overview/expenses/**`, `/owner/retention`,
      `/admin/manual-adjustments` not yet individually re-verified against this same empirical test —
      assume "probably also degrades gracefully already" only after checking, not from the pattern.
- [x] 6b.3b Fixed via the preemptive-check mechanism its own note above described: `/owner/overview`,
      `/owner/overview/net`, `/admin/prepaid`, `/admin/owner-customers`, `/admin/redos`,
      `/owner/marketing`, `/owner/retention` all now check `serverApi.getSquareConnection()
      .accessTokenSet` up front and render `SetupRequiredNotice` before making the (now-pointless)
      data call. `/admin/redos`, `/owner/marketing`, `/owner/retention` are reachable by MANAGER/
      ADS_MANAGER, who can't call `/owner/settings/square` (OWNER-only) — that call `.catch(() =>
      null)`s and fails open (skips the check, falls through to the normal page) for those roles,
      same reasoning as the CTA link itself being conditional on `me.role === 'OWNER'`. Verified in
      an isolated instance: all 7 pages show the notice for an unconnected business, all 7 render
      normally for Business A (connected) — no regression. `/owner/overview/expenses/**` and
      `/admin/manual-adjustments` confirmed not Square-dependent at all (no `SquareClientProvider` in
      their service chain) — correctly left untouched.
- [x] 6b.4 **Telegram/SMS/RAG shipped 2026-08-18, as a live incident** — the instant 2.6 removed
      the old Business-A-only block, AK PMU's own owner opened Settings > Telegram/SMS and the RAG
      admin page and got a raw 500 instead ("telegram_notification_config missing for business 2",
      same for twilio_sms_config and rag_agent_config): those services all assumed their
      per-business row already existed and nothing ever created it for a business made via
      `POST /api/platform/businesses`. Fixed two ways — `BusinessProvisioningService.create()` now
      seeds an empty (all-null-credential) telegram/twilio row for every new business (their own
      "off" representation, doesn't enable anything); `RagConfigService` gets a non-throwing
      `findActive()` used only by the settings-page GET, since "no active RAG config" is RAG's own
      correct "not enabled for this business yet" representation (tasks.md 7.4), not a failure.
      Business 2 backfilled directly, verified against the live crash. KB articles/SOPs checked:
      not exposed to this crash class at all — they're list-shaped tables (many rows per business,
      like `sms_message`), not a singleton "exactly one config row per business" the way telegram/
      twilio/RAG config are, so there's no missing-row `.orElseThrow()` to hit.

## Phase 7 — Second salon (Business B / AK PMU) onboarding

- [x] 7.1 Create Business B's `business` row, connect its Square production credentials — shipped
      2026-08-15 via `POST /api/platform/businesses` + `/owner/settings/square` (PR #368), not the
      originally-planned Phase 6.4 `/onboarding` flow, which was folded into the Business Settings
      admin UI instead
- [x] 7.2 Enter Business B's `salon_config` values — 45%/55% commission, tier bonus off, 3.5% card tip
      fee, via `/owner/settings/business` (PR #368)
- [ ] 7.3 Invite Business B's ~2 providers as `app_user` rows with `PROVIDER` role — not yet done,
      but confirmed no code is needed: `/admin/users` + `UserController` were already fully
      business-scoped (`currentBusinessContext.id()` throughout) before tonight — this is purely a
      user action for the owner to take via the existing UI whenever ready
- [x] 7.4 At the time this landed, business_feature-style gating wasn't built yet (2.6/4.3 were both
      still open; 4.3 shipped 2026-08-18, see its own entry), so this landed as a stricter
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
- [x] 8.3 Green — `CrossTenantIsolationTest` (25 tests, Phase 2.5), `SquareClientProviderTest`/
      `SquareWebhookControllerTest` (Phase 3.9), `UserControllerTest`/`ProviderControllerTest`/
      `PayPeriodControllerTest` (Phase 5.4) all pass against a real, fresh Postgres.
- [x] 8.4 Already covered — `CrossTenantIsolationTest#ragChunkVectorSearchIsolation` (see that
      test's own doc comment): orthogonal one-hot embedding vectors prove business A's search never
      returns business B's chunk even when it's the objectively nearest match, not just "never
      returns it when it also happens to be farther away."
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
