## ADDED Requirements

### Requirement: AI triage endpoint returns a structured classification + explanation + draft message
The system SHALL expose `POST /api/suspicious/{bookingId}/triage?year=<int>&month=<int>` that, for a booking already flagged by the suspicious-booking detector in the requested month, returns a `TriageResult` containing:
- `classification` — one of `LIKELY_LEGIT`, `NEEDS_REVIEW`, `LIKELY_FRAUD`
- `confidence` — decimal between `0.0` and `1.0`
- `explanation` — 2-3 sentence plain-English explanation citing which detection signals fired
- `draftMessage` — 1-3 sentence professional-tone message the owner can copy/paste to send the provider
- `signals` — array of signal names the explanation cites
- `promptVersion` — the prompt version used to produce this triage
- `model` — the model identifier used

The classification field SHALL be one of exactly three enum values; the API SHALL NOT return any other classification string under any circumstances.

#### Scenario: Triage returns a valid classification
- **WHEN** an owner calls `POST /api/suspicious/booking-123/triage?year=2026&month=6` for a booking that the detector has flagged as suspicious
- **THEN** the response is 200 OK with a body whose `classification` is one of `LIKELY_LEGIT`, `NEEDS_REVIEW`, `LIKELY_FRAUD`, `confidence` is in `[0.0, 1.0]`, and `explanation` is a non-empty string

#### Scenario: Triage for a non-suspicious booking is rejected
- **WHEN** an owner calls `POST /api/suspicious/booking-999/triage` for a booking that the detector has NOT flagged
- **THEN** the response is 404 Not Found

### Requirement: Triage results are cached per (booking_id, prompt_version)
The system SHALL persist every `TriageResult` to a `suspicious_triage` table with `UNIQUE(square_booking_id, prompt_version)`. Repeat calls for the same `bookingId` under the same `PROMPT_VERSION` SHALL return the cached result without calling the LLM. When `PROMPT_VERSION` changes between calls, the new call SHALL produce a fresh triage under the new version.

#### Scenario: Repeat click returns cached result
- **WHEN** an owner calls triage for the same booking twice in succession with no prompt-version change in between
- **THEN** the second call returns the same `TriageResult` as the first and the LLM is NOT invoked a second time

#### Scenario: New prompt version produces fresh triage
- **WHEN** an owner calls triage for a booking that has a cached triage under prompt version `v1` and the deployed `PROMPT_VERSION` is now `v2`
- **THEN** the LLM is invoked and a new row is persisted with `prompt_version = "v2"`; the `v1` row is preserved

### Requirement: Owner feedback on triages is captured and shipped to LangSmith
The system SHALL expose `POST /api/suspicious/{bookingId}/triage/feedback` accepting `{ helpful: boolean, correctedClassification: Classification? }`. The endpoint SHALL persist `helpful` and (if provided) `correctedClassification` to the matching `suspicious_triage` row, and SHALL ship a corresponding feedback event to LangSmith linked to the original triage's `langsmith_run_id`.

#### Scenario: Owner marks a triage unhelpful with a correction
- **WHEN** an owner posts `{ helpful: false, correctedClassification: "LIKELY_LEGIT" }` for a triage that classified the booking as `LIKELY_FRAUD`
- **THEN** the `suspicious_triage` row updates `helpful = false` and `corrected_classification = "LIKELY_LEGIT"`, and a LangSmith feedback event is shipped with a low score and the correction recorded

#### Scenario: Owner marks a triage helpful with no correction
- **WHEN** an owner posts `{ helpful: true }`
- **THEN** the row updates `helpful = true`, `corrected_classification` is left null, and a LangSmith feedback event with a positive score is shipped

### Requirement: Clear and Undo actions on triaged bookings ship implicit feedback
When an owner Clears a booking that has an existing triage, the system SHALL ship a LangSmith feedback event linked to the triage's `langsmith_run_id`, scoring agreement between the LLM classification and the owner's clearance action (Cleared with a refund/comp note → score 0 if the LLM said `LIKELY_FRAUD`, score 1 if the LLM said `LIKELY_LEGIT` or `NEEDS_REVIEW`). Undo SHALL ship a corresponding retraction event.

#### Scenario: Owner Clears a booking the LLM flagged as fraud, with a refund note
- **WHEN** an owner Clears a booking with note `"this was a refund"` and the booking has a cached triage with `classification = LIKELY_FRAUD`
- **THEN** a LangSmith feedback event is shipped with score `0` and metadata `{ owner_action: "clear_with_note", note: "this was a refund", llm_classification: "LIKELY_FRAUD" }`

#### Scenario: Owner Undoes a previous Clear
- **WHEN** an owner Undoes a clearance on a booking that previously shipped a LangSmith feedback event
- **THEN** a retraction feedback event is shipped referencing the original feedback's run ID

### Requirement: Triage endpoints are gated to OWNER + MANAGER roles
The system SHALL gate `POST /api/suspicious/{bookingId}/triage` and `POST /api/suspicious/{bookingId}/triage/feedback` to `hasAnyRole('OWNER', 'MANAGER')` via the existing `/api/suspicious/**` matcher in `SecurityConfig`. Providers SHALL NOT be able to call either endpoint.

#### Scenario: Provider triage call is blocked
- **WHEN** a user authenticated as PROVIDER calls `POST /api/suspicious/booking-123/triage?year=2026&month=6`
- **THEN** the response is 403 Forbidden and no LLM call is made

### Requirement: Feature flag controls availability per deployment
The system SHALL read the `AI_TRIAGE_ENABLED` environment variable on startup. When `AI_TRIAGE_ENABLED` is `false` (the default), `POST /api/suspicious/{bookingId}/triage` and `POST /api/suspicious/{bookingId}/triage/feedback` SHALL return 404 Not Found, and the frontend SHALL NOT render the Explain button on the suspicious-bookings page.

#### Scenario: Feature flag disabled returns 404
- **WHEN** the backend boots with `AI_TRIAGE_ENABLED=false` and an owner calls `POST /api/suspicious/booking-123/triage?year=2026&month=6`
- **THEN** the response is 404 Not Found and no LLM call is made

#### Scenario: Feature flag enabled allows triage
- **WHEN** the backend boots with `AI_TRIAGE_ENABLED=true` and an owner calls triage for a flagged booking
- **THEN** the triage proceeds normally (cache-or-call → return `TriageResult`)

### Requirement: Triage response is a single JSON object
The system SHALL respond to `POST /api/suspicious/{bookingId}/triage?year=&month=` with a single `application/json` body containing the parsed `TriageResult`. Streaming via Server-Sent Events was considered and rejected: structured outputs constrain the model to emit JSON, and streaming partial JSON tokens to the UI is the wrong UX — the right UX for the ~1-2 second Haiku call is a spinner, not partial JSON. The frontend renders a loading indicator while the call is in flight.

#### Scenario: Successful triage returns the result as JSON
- **WHEN** an owner calls triage on a flagged booking
- **THEN** the response is `Content-Type: application/json` with a body containing all `TriageResult` fields (`classification`, `confidence`, `explanation`, `draftMessage`, `signals`, `promptVersion`, `model`)

#### Scenario: Cached response is delivered identically to a fresh response
- **WHEN** an owner calls triage on a cache-hit booking
- **THEN** the response shape and content type are identical to a fresh triage — the only difference is latency (the cache hit returns in milliseconds; the fresh call takes ~1-2 seconds)

### Requirement: Every LLM call is traced to LangSmith with required tags
The system SHALL ship a LangSmith trace for every Claude API call made by triage. Each trace SHALL include the tags `bookingId`, `providerId`, `promptVersion`, and `model`, the full input messages array, the parsed output, token usage (input, cached, output), and total latency. Trace shipping SHALL be asynchronous and non-blocking; a LangSmith outage SHALL NOT cause the user-facing API call to fail.

#### Scenario: Successful call produces a trace
- **WHEN** an owner triggers a triage that misses the cache and successfully calls Claude
- **THEN** within 5 seconds a LangSmith trace exists with the required tags, inputs, outputs, usage, and latency

#### Scenario: LangSmith outage does not break the user response
- **WHEN** LangSmith's API is unreachable and an owner calls triage
- **THEN** the triage call still succeeds and returns the `TriageResult`; the trace ship failure is logged but not surfaced to the user

### Requirement: Failure modes degrade gracefully and never leak raw model output
The system SHALL handle three classes of LLM failures distinctly:

1. **Anthropic API errors (5xx, timeouts).** Retry once with 1-second backoff. If still failing, return 502 Bad Gateway with a friendly error message (no stack trace, no raw model output).
2. **Schema-validation failures (model returned non-conforming output).** Log the raw response with the LangSmith run ID; return 502 Bad Gateway with the same friendly message. Never expose raw output to the UI.
3. **Refusal (`stop_reason: refusal`).** Persist the refusal category to `suspicious_triage`; return 200 OK with a `TriageResult` whose `classification = NEEDS_REVIEW`, `confidence = 0.0`, and `explanation` contains a friendly message indicating manual review is needed.

#### Scenario: Schema violation returns 502 without leaking output
- **WHEN** the LLM returns malformed output that fails schema validation
- **THEN** the response is 502 Bad Gateway with body `{ "error": "AI explanation unavailable; please try again or review manually." }` and the raw model output is logged but NOT included in the response body

#### Scenario: Refusal returns NEEDS_REVIEW
- **WHEN** the LLM returns `stop_reason: refusal`
- **THEN** the response is 200 OK with `classification = NEEDS_REVIEW`, `confidence = 0.0`, and the `explanation` field contains text like `"This booking couldn't be classified automatically; please review manually."`

### Requirement: Triage UI on the suspicious-bookings detail page
The `/reports/{providerId}/suspicious` page SHALL, when `AI_TRIAGE_ENABLED` is true, render an "Explain" button on each suspicious-booking row. Clicking the button SHALL call the triage endpoint and render the streamed explanation, the classification chip (with visual emphasis by confidence), the draft message with a Copy button, and a thumbs-up/thumbs-down feedback widget.

#### Scenario: Explain button hidden when feature flag is off
- **WHEN** an owner loads the suspicious-bookings page and the feature flag is `false`
- **THEN** no Explain button is rendered on any row

#### Scenario: Explain button shows streamed result
- **WHEN** an owner clicks Explain on a row and the feature flag is `true`
- **THEN** the row expands to show text streaming from the LLM, then the final classification chip, draft message + Copy button, and a thumbs-up/thumbs-down feedback widget

#### Scenario: Copy button copies the draft message
- **WHEN** an owner clicks the Copy button next to a draft message
- **THEN** the draft message text is copied to the system clipboard
