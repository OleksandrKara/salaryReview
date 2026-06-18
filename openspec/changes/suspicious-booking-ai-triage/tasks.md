## 1. Backend — Dependencies and config

- [x] 1.1 Add `com.anthropic:anthropic-java:2.40.1` to `backend/pom.xml`; verify it resolves and `./mvnw compile` is clean
- [x] 1.2 Add `ANTHROPIC_API_KEY`, `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT` (default `salary-review-suspicious-triage`), `AI_TRIAGE_ENABLED` (default `false`) to `docker-compose.yml` backend service env block, following the existing `SQUARE_ACCESS_TOKEN` pattern (`${VAR:-default}`)
- [x] 1.3 Add the same env vars (empty values) to `.env.example` with comments pointing to console.anthropic.com and smith.langchain.com
- [x] 1.4 Add `@ConfigurationProperties("ai.triage")` config class `AiTriageProperties` exposing `enabled`, `anthropicApiKey`, `langsmithApiKey`, `langsmithProject`; wire defaults from env vars in `application.yml`

## 2. Backend — Migration and persistence

- [x] 2.1 Create Flyway migration `V21__suspicious_triage.sql` with table `suspicious_triage` (id BIGSERIAL PK, square_booking_id VARCHAR(255) NOT NULL, prompt_version VARCHAR(32) NOT NULL, classification VARCHAR(32) NOT NULL, confidence NUMERIC(4,3) NOT NULL, explanation TEXT NOT NULL, draft_message TEXT NOT NULL, signals_json JSONB NOT NULL, model VARCHAR(64) NOT NULL, langsmith_run_id VARCHAR(64) NULLABLE, refusal_category VARCHAR(64) NULLABLE, helpful BOOLEAN NULLABLE, corrected_classification VARCHAR(32) NULLABLE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()) + UNIQUE(square_booking_id, prompt_version) + index on `created_at DESC`
- [x] 2.2 Create `SuspiciousTriage` JPA entity in `com.salonreview.domain` (the existing convention keeps entities flat in `domain/` even for feature modules — followed)
- [x] 2.3 Create `TriageClassification` enum (`LIKELY_LEGIT`, `NEEDS_REVIEW`, `LIKELY_FRAUD`) in `com.salonreview.domain` (renamed from generic `Classification` for searchability)
- [x] 2.4 Create `SuspiciousTriageRepository extends JpaRepository<SuspiciousTriage, Long>` with finder `findBySquareBookingIdAndPromptVersion(String, String)` and mutator `updateFeedback(id, helpful, correctedClassification)`

## 3. Backend — AI module skeleton

- [x] 3.1 Create package `com.salonreview.ai`; add `AnthropicClientConfig` with conditional `@Bean` exposing `com.anthropic.client.AnthropicClient` from `AiTriageProperties.anthropicApiKey()` — bean only registered when `ai.triage.enabled=true`
- [x] 3.2 Create `TriageResult` Java record per design D3 with `@JsonPropertyDescription` annotations
- [x] 3.3 Define `PROMPT_VERSION = "v1"` constant in `TriagePrompts` (cleaner than putting it in the service — keeps prompts in one place)
- [x] 3.4 Author `SYSTEM_PROMPT_V1` as a Java text-block in `TriagePrompts` — rubric for all 3 classifications, full signal taxonomy, schema commentary, 4 few-shot examples (refund/new-customer-weekend/repeat-customer-high-value/gross-unknown), owner-comp reminder. (Size will need re-check vs 4096-token minimum once we have real eval data — see Chunk E task list.)

## 4. Backend — Triage service

- [x] 4.1 Implement `SuspiciousBookingTriageService.triage(bookingId, year, month)`: cache lookup → candidate lookup via existing `SuspiciousBookingService.findCandidateForTriage` → Claude call → persist → return. **Deviation from task description:** added `year`/`month` query params because the existing detector is keyed by year+month and the URL needs to know which month's candidate set to search.
- [x] 4.2 Wrap the Claude call with `LangSmithTracer.startTrace(...)` / `trace.complete(...)`; null-safe when tracer bean isn't registered (LangSmith unconfigured or feature off)
- [x] 4.3 Handle failure modes per design D12: refusal → persist `refusal_category` + return synthetic `NEEDS_REVIEW`; API errors → log + throw `TriageFailedException` (translated to 502 by controller layer in Chunk C). **Retry-once not implemented yet — TODO for Chunk C** (depends on whether `./mvnw test` surfaces transient failures in real use)

## 5. Backend — LangSmith integration

- [x] 5.1 Create `LangSmithClient` — direct `java.net.http.HttpClient` wrapper around `/runs` (POST + PATCH) and `/runs/{id}/feedback` (POST); reads keys from `AiTriageProperties`; all methods return `boolean`, log+swallow failures
- [x] 5.2 `LangSmithTracer.startTrace(name, tags, inputs)` returns a `Trace` handle; the create-run POST ships async on a small dedicated executor; tags include `bookingId`, `providerId`, `promptVersion`, `model`
- [x] 5.3 `LangSmithTracer.feedback(runId, score, key, metadata)` — async, non-blocking, swallows failures
- [x] 5.4 Wired Clear/Undo into LangSmith via `TriageFeedbackPublisher` interface (`DefaultTriageFeedbackPublisher` impl, conditional bean). `SuspiciousBookingService` takes `ObjectProvider<TriageFeedbackPublisher>` so the existing service boots cleanly when AI is off. Score convention per design D9: LIKELY_LEGIT+Clear=1.0, NEEDS_REVIEW+Clear=0.5, LIKELY_FRAUD+Clear=0.0; Undo always 0.5 with metadata.

## 6. Backend — Controller and security

- [x] 6.1 Created `SuspiciousTriageController` with both endpoints. **Deviation:** dropped SSE streaming per Chunk B finding (structured outputs incompatible with token-by-token streaming UX); returns single JSON body. Added `year`/`month` query params on the triage endpoint (controller needs to know which month to search). Feature-flag-off → 404 via early return.
- [x] 6.2 Verified `/api/suspicious/**` matcher in `SecurityConfig` already covers the new paths — no security wiring change needed. Confirmed at runtime: unauthenticated requests get 401 (security applied), not 404 (route exists).
- [x] 6.3 Created `TriageExceptionHandler` (`@RestControllerAdvice` scoped to `com.salonreview.web`) mapping `TriageFailedException` → 502 with generic user-facing message. Stack traces logged server-side, never surfaced to client. Order(0) so it doesn't get swallowed by generic handlers.

## 7. Backend — Tests

- [x] 7.1 `SuspiciousBookingTriageServiceTest` — 9 tests covering: flag-off short-circuit, cache hit (no LLM call), non-flagged booking → empty, cache miss → LLM + persist (with prompt_version/model overwrite), prompt-version change → fresh triage, recordFeedback paths. Spy pattern on `callClaude` allows canned responses without standing up the real SDK.
- [x] 7.2 `SuspiciousTriageControllerTest` — 6 tests covering: flag-off → 404, success → 200 with full JSON body, non-flagged → 404, `TriageFailedException` → 502 with generic message, feedback success → 200, feedback no-row → 404. Standalone MockMvc setup (no Spring Security imported). **Deviation:** skipped explicit PROVIDER → 403 test — the role gating comes from the existing `/api/suspicious/**` matcher in `SecurityConfig`, which is already exercised by the existing suspicious-bookings endpoints; re-testing it here would be duplication.
- [ ] 7.3 `LangSmithTracerTest` — **deferred to a follow-up PR**. The tracer is thin (HTTP wrapper around 3 endpoints, async dispatch via small executor). Testing async behavior reliably requires more harness setup than the value justifies right now; will revisit if we see real failures in production traces.
- [x] 7.4 Refusal scenario — covered in `SuspiciousBookingTriageServiceTest.refusalProducesFallbackAndPersistsCategory`: fake `callClaude` throws `RefusalException("cyber")`; verify result is NEEDS_REVIEW with confidence 0, explanation contains "couldn't be classified" and the category, and the persisted row has `refusalCategory = "cyber"`.

## 8. Frontend — Types and proxy

- [x] 8.1 Added `TriageClassification`, `TriageResult`, `TriageFeedbackRequest`, and `Features` types to `frontend/app/lib/types.ts`; extended `Me` with `features: Features`.
- [x] 8.2 Created `frontend/app/api/suspicious/[bookingId]/triage/route.ts` (POST). **Deviation:** no SSE proxy — the backend returns JSON (SSE dropped in Chunk B). Route extracts `year`/`month` from query params, returns 400 if missing.
- [x] 8.3 Created `frontend/app/api/suspicious/[bookingId]/triage/feedback/route.ts` (POST) — thin `forwardToBackend` wrapper.
- [x] 8.4 Added `requestTriage(bookingId, year, month)` + `submitTriageFeedback(bookingId, helpful, corrected)` to `app/lib/api.ts`.

## 9. Frontend — Suspicious-bookings page UI

- [x] 9.1 Added `aiTriageEnabled: boolean` to the response of the existing `/api/me` endpoint (via a new `features` object) — **better than a new endpoint** because `getMe()` is already called on every page load; no extra round-trip. Backend `MeController` reads from `AiTriageProperties`.
- [x] 9.2 Added `TriagePanel` component with an Explain button rendered per row in `SuspiciousList`. Conditionally rendered only when `aiTriageEnabled=true`.
- [x] 9.3 On click, fetch the triage from the proxy route (single non-streaming call — SSE dropped). Renders idle → loading → loaded / error states.
- [x] 9.4 Loaded state renders: classification chip (emerald/amber/red ring by class, opacity 0.4-1.0 by confidence), explanation, model + prompt version metadata line, draft message in a styled block with Copy button (with "Copied!" feedback for 1.5 sec).
- [x] 9.5 Thumbs-up sends `helpful:true, corrected:null`. Thumbs-down toggles a 3-button corrected-classification picker (each chip styled by class). After feedback sends, the widget is replaced with "Thanks — feedback recorded." Feedback failures are silent (the user already got value from the triage).

## 10. Eval bootstrap (one-time)

- [x] 10.1 Created `TriageEvalBootstrapController` exposing `POST /api/owner/triage-eval-bootstrap?year=&month=` (inherits OWNER-only gate from `/api/owner/**`; `@ConditionalOnProperty` so the bean is absent when the feature flag is off). **Deviation:** chose a one-shot HTTP endpoint over the CLI runner described in the original task — simpler to invoke (single `curl`), no profile/restart needed, and the owner is already authenticated via the browser session. Iterates `suspicious_booking_clearance` rows with non-null notes, calls `triageService.triage(...)` for each (which handles cache + LLM + LangSmith trace), and returns a summary `{month, labeledClearances, triaged, notFlaggedThisMonth, errorCount, errors[]}`.
- [ ] 10.2 **(user-driven, after enabling the feature locally)** Run the bootstrap once against the local dev DB:
   ```bash
   curl -X POST -b "sid=<session-cookie>" \
     "http://localhost:8080/api/owner/triage-eval-bootstrap?year=2026&month=6"
   ```
   Verify (a) summary JSON returned with `triaged > 0`, (b) traces appear in LangSmith project `salary-review-suspicious-triage` tagged `promptVersion=v1`, (c) `suspicious_triage` table has new rows: `docker compose exec postgres psql -U salon -d salonreview -c "SELECT square_booking_id, classification, confidence FROM suspicious_triage;"`

## 11. Verification

- [x] 11.1 `./mvnw test` — passes for all new tests (18 across `SuspiciousBookingTriageServiceTest`, `SuspiciousTriageControllerTest`, plus the existing `SuspiciousBookingServiceTest` still works with the new constructor). Full suite: 80/81 — the 1 fail is the pre-existing `SalonreviewApplicationTests.contextLoads` which needs a DB connection and passes only in CI (documented in `CLAUDE.md`).
- [x] 11.2 `npx tsc --noEmit` in `frontend/` — clean (verified in Chunk D).
- [x] 11.3 `docker compose up -d --build` — V21 migration applies cleanly; backend healthy (`/actuator/health` green); confirmed by `docker inspect salonreview-backend --format '{{.State.Health.Status}}'`.
- [ ] 11.4 **(user-driven)** With `AI_TRIAGE_ENABLED=false` (the current default): load `localhost:3000/reports/{providerId}/suspicious?year=&month=&half=` as `olexandr.kara2` — verify NO Explain button on rows. Confirm `curl -X POST -b "sid=<cookie>" "localhost:8080/api/suspicious/<bookingId>/triage?year=2026&month=6"` returns **404**.
- [ ] 11.5 **(user-driven; first paid Claude call!)** Set `AI_TRIAGE_ENABLED=true` in `.env`, `docker compose up -d backend`. Click Explain on a real flagged booking. Verify: (a) loading state shown for ~1-2 sec, (b) classification chip + explanation + draft message + Copy + thumbs widgets render, (c) the LangSmith trace appears within ~5 sec at smith.langchain.com with tags `bookingId`, `providerId`, `promptVersion=v1`, `model=claude-haiku-4-5`. Cost: ~$0.005 per click.
- [ ] 11.6 **(user-driven)** Click Explain on the SAME booking again — verify it returns ~instantly (cache hit), NO new LangSmith trace appears, NO Anthropic API call in `docker compose logs backend`.
- [ ] 11.7 **(user-driven)** Click thumbs-down → pick a corrected classification. Verify: (a) widget updates to "Thanks — feedback recorded.", (b) `docker compose exec postgres psql -U salon -d salonreview -c "SELECT helpful, corrected_classification FROM suspicious_triage WHERE square_booking_id='<id>';"` shows the values, (c) LangSmith trace gains a feedback event with `key=owner_explicit_feedback`, score=0.0.
- [ ] 11.8 **(user-driven)** Click Clear (with or without a note) on a booking that has a triage. Verify a `key=owner_clear_action` feedback event appears in LangSmith on the original trace with score per design D9 (LIKELY_LEGIT→1.0, NEEDS_REVIEW→0.5, LIKELY_FRAUD→0.0).
- [ ] 11.9 **(user-driven, optional failure-mode check)** Set `ANTHROPIC_API_KEY=invalid` in `.env`, `docker compose up -d backend`. Click Explain. Verify: response is **502** with body `{"error":"AI explanation unavailable; please try again or review manually."}`, UI shows "Try again", `docker compose logs backend` has the real cause logged server-side but it's NOT in the response.
- [ ] 11.10 **(user-driven, optional failure-mode check)** Set `LANGSMITH_API_KEY=invalid` in `.env`, restore valid `ANTHROPIC_API_KEY`, `docker compose up -d backend`. Click Explain. Verify: triage succeeds (200 with TriageResult), `docker compose logs backend` has a WARN about LangSmith failure, the user-facing response is unaffected — proves trace ship failures are non-blocking.
