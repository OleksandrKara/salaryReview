## Why

Today, when an owner reviews flagged suspicious bookings on `/reports/[providerId]/suspicious`, they get a list of rows with `date / customer / service / gross` but no narrative — they have to mentally reconstruct *why* each booking was flagged and decide whether it's a refund, a comp the owner forgot to record, a Square reconciliation gap, or actual provider fraud. For a backlog of dozens of rows that's slow and error-prone, and there's no consistent language to take to the provider when something needs a conversation.

This change layers an LLM-driven triage on top of the existing detection: per flagged booking, the owner can on-demand request (a) a plain-English explanation citing the specific signals that fired, (b) a classification with confidence (`likely_legit | needs_review | likely_fraud`), and (c) a draft message the owner can copy/paste to send the provider. The owner's subsequent Clear / Undo decisions are recorded as labels and shipped to LangSmith, building a real eval dataset the prompt can be regression-tested against from day one.

This is also feature #1 of three planned AI features ([[salaryreview-ai-roadmap]]) — chosen first because it has labeled data already, a small blast radius (read-only, owner-gated), and exercises the full production-AI stack (structured outputs, LLM-as-judge, guardrails, observability, evals).

## What Changes

- **Backend AI module** under `backend/src/main/java/com/salaryreview/ai/` (new package):
  - `AnthropicClient` — thin wrapper over the official Anthropic Java SDK (`com.anthropic:anthropic-java`), reads `ANTHROPIC_API_KEY` env var. No LangChain.
  - `SuspiciousBookingTriageService` — for a given `bookingId`, gathers the suspicious-booking context (signals that fired, provider history snapshot, service info, customer hint), calls Claude with a structured-output JSON schema, returns `TriageResult { classification, confidence, explanation, draftMessage, signals[] }`.
  - `LangSmithTracer` — wraps every Claude call as a LangSmith trace; tags `bookingId`, `providerId`, `tenantId`, `promptVersion`. Implemented as a thin HTTP client against the LangSmith Runs API (no LangSmith SDK on Maven Central as of writing — see design).
  - Prompt versioning — prompt template is a Java string constant tagged `PROMPT_VERSION = "v1"`. Future versions are added inline; LangSmith tags every trace with the version so we can A/B by filter.
- **Guardrails:** structured-output JSON schema enforced via the Anthropic SDK's `tools` (forced tool call). Output validated server-side; on schema violation or unsafe content the endpoint returns 502 and logs but does not surface the raw model output.
- **New endpoint:** `POST /api/suspicious/{bookingId}/triage` — returns cached `TriageResult` if one exists for this booking + current `PROMPT_VERSION`; otherwise calls Claude, persists, and returns. Owner-and-manager only. Idempotent within a prompt version.
- **New endpoint:** `POST /api/suspicious/{bookingId}/triage/feedback` — owner records `helpful: boolean` and optional `corrected_classification` for the displayed triage. Persisted to DB and shipped to LangSmith as a `feedback` event on the original run.
- **Eval dataset:** new background hook on the existing Clear / Undo endpoints — when an owner Clears with a note, or Undoes, we ship a LangSmith `feedback` event labelling the prior triage as agreeing/disagreeing. Over time the existing Clear-with-note rows form a labeled regression set.
- **New DB table:** `V21__suspicious_triage.sql` with columns `square_booking_id`, `prompt_version`, `classification`, `confidence`, `explanation`, `draft_message`, `signals_json`, `model`, `langsmith_run_id`, `created_at`, `helpful` (nullable), `corrected_classification` (nullable). UNIQUE(`square_booking_id`, `prompt_version`).
- **Frontend (Next.js App Router):**
  - On `/reports/[providerId]/suspicious`, each row gets an **"Explain"** button. Click → calls the new proxy route `app/api/suspicious/[bookingId]/triage/route.ts`, shows a spinner, then renders explanation + classification chip + draft-message block with **Copy** button.
  - Below: thumbs-up/thumbs-down feedback widget posting to `/triage/feedback`.
  - Streaming: response is streamed (SSE-style chunks) so the owner sees text appearing rather than waiting 3-5s for the full result.
- **Config:** new env vars in `docker-compose.yml` + `.env.example`: `ANTHROPIC_API_KEY`, `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT` (default `salary-review-suspicious-triage`), `AI_TRIAGE_ENABLED` (feature flag, default `false`; manually set to `true` per-tenant later).
- **Non-goals:**
  - No automatic Clear / Undo by the AI. Classification informs the owner, never acts.
  - No batch / eager triage of all flagged bookings — strictly on-demand per row (see design D2).
  - No model selection UI — single hard-coded model per `PROMPT_VERSION`.
  - No prompt editing UI — prompts live in code, evolve via PRs, versioned by string constant.
  - No fine-tuning in V1. Eval data is collected; tuning is a future feature.
  - No exposure to providers. Owner + manager only.
  - Not a replacement for the human Clear / Undo workflow. The owner still decides.

## Capabilities

### New Capabilities
- `suspicious-booking-ai-triage`: On-demand LLM-generated explanation, classification, and draft provider-message for any flagged suspicious booking, with the result cached per prompt-version and every call observed in LangSmith. Owner feedback ships back to LangSmith as graded runs, forming a regression eval dataset.

### Modified Capabilities
*(none — `suspicious-bookings` spec gains additive endpoints + a new UI button on its detail page, but no existing requirement changes)*

## Impact

- **Backend**: New `com.salaryreview.ai` package (~6 classes). New `SuspiciousTriageController`. New `SuspiciousTriage` entity + repo. Two new endpoints on the existing `/api/suspicious` tree. `SecurityConfig` rule unchanged (the new endpoints sit under the already-gated `/api/suspicious/**`). No change to detection logic or `SuspiciousBookingService`.
- **DB**: Flyway `V21__suspicious_triage.sql`.
- **Frontend**: `app/reports/[providerId]/suspicious/page.tsx` and its client list component get the Explain button + result panel. New proxy routes under `app/api/suspicious/[bookingId]/triage/` and `.../triage/feedback/`. New `TriageResult` type.
- **Dependencies (Maven)**: add `com.anthropic:anthropic-java:0.x`. No LangChain. LangSmith integration is direct HTTPS calls — no new SDK dep.
- **Secrets / config**: three new env vars (`ANTHROPIC_API_KEY`, `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT`) wired through `application.properties` → `@Value` injection. Feature flag `AI_TRIAGE_ENABLED` defaults off so this can ship dark.
- **Verification**:
  - Backend unit test of `SuspiciousBookingTriageService` with a fake `AnthropicClient` returning canned structured output (validates parsing, persistence, cache-hit behavior).
  - Backend integration test of the two new endpoints under the existing `MockMvc` setup, including the role-gating denial test.
  - Frontend tsc / build clean on PR.
  - Manual check at `localhost:3000/reports/{providerId}/suspicious?...` logged in as `olexandr.kara2`: click Explain on a real flagged booking → see streamed explanation, classification chip, draft message; verify a trace appears in LangSmith UI.
  - LangSmith eval run: a smoke `pytest` (or Java JUnit) that pulls ~10 already-Cleared bookings + their owner notes, runs them through triage, and asserts classification matches a regex of expected labels (regression guard before merging prompt changes).
