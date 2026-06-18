## Context

The existing suspicious-bookings detector ([[suspicious-bookings spec]]) flags bookings that meet a hard set of conditions (past, in-status, no order match, no cash note, not an owner customer). The owner is left to scan a list and decide what each row actually is — a refund the owner forgot to enter, a comp the owner forgot to record, a Square reconciliation gap, or actual provider fraud. With backlogs of dozens of rows that's slow and inconsistent.

This change adds an LLM-driven explanation/classification layer on top of detection. The detector still owns "is this booking suspicious?"; the LLM owns "given that it's suspicious, what's the most likely cause and what should the owner do?" The two stay decoupled — the LLM is never in the gate that decides whether something is flagged.

The feature is also the first of three planned AI features ([[salaryreview-ai-roadmap]]) and intentionally exercises the full production-AI stack (structured outputs, evals on labeled data, observability) so the patterns are in place before features #2 and #3.

## Goals / Non-Goals

**Goals:**
- For any flagged suspicious booking, produce on-demand: a plain-English explanation, a 3-way classification with confidence, and a copy-paste-able draft message to the provider.
- Cache the result per `(square_booking_id, prompt_version)` so repeat clicks cost zero LLM dollars.
- Wire LLM observability + a labeled eval dataset from day 1, so prompt changes can be regression-tested against real reviewed bookings before merge.
- Honor existing role gating (OWNER + MANAGER); ship dark behind a per-tenant feature flag.
- Stay decoupled from the detector — the LLM never decides whether a booking is suspicious, only explains and classifies an already-flagged one.

**Non-Goals:**
- No automatic Clear / Undo by the AI. Classification informs the owner; the owner still decides.
- No eager batch triage of every flagged booking. Strictly on-demand per click (rationale in D2).
- No prompt-editing UI, no model-selection UI. Both are code constants; new versions ship via PR.
- No fine-tuning in V1. Eval data is collected so we *could* tune later if Haiku underperforms.
- No exposure to providers. Owner + manager only — providers don't see the LLM-generated explanation, classification, or draft.
- No replacement for the existing detector. We layer on top of `SuspiciousBookingService`, never rebuild detection.
- No write to Square (existing constraint).

## Decisions

### D1: Anthropic Java SDK direct, no LangChain

**Decision:** Use the official `com.anthropic:anthropic-java:2.40.1` SDK from Maven Central. No LangChain (the framework). LangSmith (the observability product) IS used, but via direct HTTPS calls — no LangSmith Maven dep needed.

**Rationale:** LangChain abstractions leak and obscure what the model is actually doing. Direct SDK use keeps the code readable and debuggable. LangSmith is decoupled from LangChain and works fine with any client. See [[salaryreview-ai-roadmap]] for the full rejection rationale.

**Alternatives considered:**
- *LangChain4j*: same abstraction-leakage problem in Java form; adds dependency surface for no benefit at this scale.
- *Raw HTTPS to Anthropic API*: works but loses streaming helpers, structured-output parsing, and type safety. The SDK gives us those for free.

### D2: Model = Claude Haiku 4.5; documented escalation path to Sonnet 4.6

**Decision:** Default model is `claude-haiku-4-5`. If eval accuracy on the labeled set falls below ~90% after prompt-engineering passes, escalate to `claude-sonnet-4-6`. Model is a code constant tagged with `PROMPT_VERSION`.

**Rationale:** The task is classification-with-rubric + short generation — Haiku's sweet spot. Haiku is ~3× cheaper and meaningfully faster than Sonnet (latency matters; called from UI). Opus is 15× the cost of Haiku and would be overkill for a constrained-output classification task. The escalation criterion is measurable, not a guess — we'll know via the eval (D9) whether Haiku is enough.

**Alternatives considered:**
- *Start on Sonnet, drop to Haiku later*: defensible if we had no eval. We have one (owner Clear/Undo labels), so we can validate Haiku empirically from the first reviewed bookings.
- *Opus 4.7/4.8*: 15× the cost for likely no measurable accuracy gain on this task shape. Reserve for feature #3 (NL analytics agent), where multi-step reasoning actually pays.

### D3: Structured outputs via Java record + `outputConfig(Class)`

**Decision:** Define the result as a Java record; let the SDK derive the JSON schema and parse the response into a typed object:

```java
public enum Classification { LIKELY_LEGIT, NEEDS_REVIEW, LIKELY_FRAUD }

public record TriageResult(
    @JsonPropertyDescription("Classification of the suspicious booking")
    Classification classification,
    @JsonPropertyDescription("Confidence in the classification, 0.0 to 1.0")
    double confidence,
    @JsonPropertyDescription("2-3 sentence explanation citing which detection signals fired")
    String explanation,
    @JsonPropertyDescription("Draft message the owner can send the provider, 1-3 sentences, professional tone")
    String draftMessage,
    @JsonPropertyDescription("Which detection signals the explanation cites")
    List<String> signals
) {}
```

The enum is the guardrail — Haiku literally cannot return a classification outside the three allowed values.

**Rationale:** Eliminates a whole class of "model returned malformed JSON" bugs. No prompt instructions to "respond only in JSON" needed; no regex stripping of ```` ``` ```` markdown fences. The SDK handles schema derivation and response parsing.

**Alternatives considered:**
- *Tool calling with a single forced tool*: the pre-2025 workaround. Native structured outputs replaced it for pure data-shape constraints. Tool calling is the right pattern when the model should *choose* between multiple tools; structured outputs is the right pattern when you always want the same shape.
- *Raw JSON schema*: works but loses the typed `TriageResult` return value; you'd parse strings yourself.

### D4: On-demand trigger only (no eager batch)

**Decision:** Triage runs when an owner clicks "Explain" on a row. No daily cron, no eager processing of every flagged booking.

**Rationale:**
- Most flagged bookings get cleared without the owner ever needing an explanation (cash given to the front desk later, comp not yet recorded, …). Eager triage would pay LLM dollars for explanations no one reads.
- Owner-driven trigger gives clean attribution: every LLM call has an owner request behind it, which makes spend predictable and rate limits trivial to enforce.
- Cached result (D5) means second click is free.

**Alternatives considered:**
- *Eager triage of all flagged bookings*: 5-10× the cost; faster perceived UX (no spinner on first click). Bad trade for our volume.
- *Hybrid (cheap classifier eager, full triage on-demand)*: optimization worth revisiting if eager-triage cost ever becomes the dominant feature cost. Not needed in V1.

### D5: Cache by `(square_booking_id, prompt_version)` in Postgres

**Decision:** New table `suspicious_triage` with `UNIQUE(square_booking_id, prompt_version)`. On `POST /api/suspicious/{bookingId}/triage`, the backend looks up the cached row first; on hit, returns it without calling Claude. On miss, calls Claude, persists the result with the current `PROMPT_VERSION`, returns it.

**Rationale:**
- Repeat clicks on the same row are common (owner re-opens the page, owner shares the page with a manager). Zero-cost repeat is the right default.
- Keying on `(booking_id, prompt_version)` means a new prompt version automatically re-triages — historical rows under the old version are preserved (useful for eval comparison; never re-evaluated against the old version's stored result), new triages use the new version.
- Cleared / Undone bookings keep their cached triage row — useful for the eval feedback link (D9).

**Alternatives considered:**
- *In-memory cache (Caffeine, etc.)*: lost on restart, doesn't dedupe across backend pods if we ever scale horizontally.
- *Redis*: extra infra dep for no benefit at this scale. Postgres handles this fine.
- *Cache by booking_id only (ignore prompt_version)*: stale results when we ship a prompt fix.

### D6: LangSmith via direct HTTPS, no SDK dependency

**Decision:** Wrap every Claude call with a `LangSmithTracer` that POSTs to the LangSmith Runs API at call start/end and the Feedback API on owner actions. Tags every trace with `bookingId`, `providerId`, `prompt_version`, `model`. No LangSmith SDK on Maven Central — direct `HttpClient` calls, ~50 lines of Java.

**Rationale:** LangSmith's HTTP API is small and stable; an SDK would add no value. Direct calls keep the dependency surface tight and let us run the trace ship async (non-blocking — failure to ship a trace must not affect the user-facing response).

**Alternatives considered:**
- *Langfuse self-hosted*: equivalent capabilities, OSS, no per-trace billing. Heavier ops (Docker container + Postgres for the trace store). User explicitly chose LangSmith for the hosted UX and resume name-recognition.
- *Roll our own*: store traces in Postgres, build dashboards in Grafana. Months of work for zero differentiating value.

### D7: Prompt versioning as a Java string constant

**Decision:** `PROMPT_VERSION = "v1"` is a Java string constant in `SuspiciousBookingTriageService`. The prompt template, the model ID, and any few-shot examples live in code, tagged with this version. New versions bump the constant (`"v2"`, `"v3"`, …) and ship via PR. The version is recorded on every cached row and every LangSmith trace.

**Rationale:**
- Prompts are code. They version with the codebase, ship with the codebase, roll back with the codebase. Treating them as runtime config (DB rows, env vars) creates a separate change-management surface.
- LangSmith filtering by `prompt_version` tag is how we compare v1 vs v2 in the eval dashboard.
- A PR that changes the prompt is reviewable as code: the diff shows exactly what changed.

**Alternatives considered:**
- *Prompts in DB rows with a UI editor*: tempting but creates two problems: (a) prompts can be edited without code review, (b) the model/prompt pair drifts from the schema in code. Skip.
- *Prompts in YAML/properties files*: marginal benefit over Java constants; loses syntax highlighting and refactoring help.

### D8: Streaming via SSE for owner-facing UI

**Decision:** The `POST /api/suspicious/{bookingId}/triage` endpoint streams the response via Server-Sent Events. Text tokens stream to the UI as they arrive; the structured `TriageResult` is parsed server-side from the accumulated message and sent as a terminal event.

**Rationale:** First-token latency on Haiku is ~300-500ms; full response is ~1-2 sec. Streaming gives the owner immediate feedback that something is happening (the explanation starts appearing) rather than a 1-2 sec blank spinner.

**Alternatives considered:**
- *Non-streaming, just spin*: simpler client code, worse UX.
- *WebSockets*: overkill for one-shot responses; SSE is the right tool for unidirectional server-to-client.

### D9: Owner feedback → LangSmith feedback events (the eval loop)

**Decision:** Two channels of feedback flow back to LangSmith, linked to the original triage trace by `langsmith_run_id`:

1. **Explicit feedback widget:** thumbs up/down + optional `corrected_classification` on the triage UI. Persisted to `suspicious_triage.helpful` / `corrected_classification`; shipped to LangSmith as a graded run.
2. **Implicit feedback from Clear/Undo actions:** when an owner Clears a booking that had a triage, the Clear action ships a LangSmith feedback event scoring agreement (Cleared with note "refund" + LLM said `likely_fraud` → score 0; Cleared with no note + LLM said `likely_legit` → score 1).

Both channels populate the labeled eval dataset that LangSmith uses to compute the agreement / confusion matrix dashboards.

**Rationale:** The implicit channel is the high-volume path — most owners won't click thumbs up/down explicitly, but every Clear is already a decision. The explicit channel is the fine-grained path — when an owner *does* click thumbs down with a corrected classification, that's a high-signal label.

**Alternatives considered:**
- *Explicit only*: lower label volume, biased toward owners who happen to click.
- *Implicit only*: misses the fine-grained "the LLM was almost right but wrong on confidence" feedback.

### D10: Feature flag `AI_TRIAGE_ENABLED` per tenant, default off

**Decision:** New env var `AI_TRIAGE_ENABLED` (boolean, default `false`). When false, the new endpoints return 404 and the UI hides the Explain button. Ship dark; turn on tenant-by-tenant.

**Rationale:** Lets us ship the code, verify in production with `olexandr.kara2`, then enable for other tenants only after measurement. Also a kill switch if LangSmith or Anthropic has an outage — flip off, feature degrades gracefully.

**Alternatives considered:**
- *No flag, always on*: harder to roll back without a code deploy.
- *Per-user flag*: not yet needed; tenant granularity is enough for V1.

### D11: API key custody is per-deployment in V1

**Decision:** `ANTHROPIC_API_KEY` and `LANGSMITH_API_KEY` are deployment-level env vars (loaded from `.env` via docker-compose, same pattern as `SQUARE_ACCESS_TOKEN`). Every tenant's LLM calls hit the same Anthropic account; LangSmith traces are tagged with `tenantId` for per-tenant analysis.

**Rationale:** Operational simplicity. Per-tenant keys are a future feature when (a) tenants demand it for compliance, (b) we want to bill them their own LLM usage, or (c) we need per-tenant rate-limit isolation. None of those are true today.

**Alternatives considered:**
- *Per-tenant keys from launch*: premature; adds key-management UI and rotation flow before we have a customer asking for it.

### D12: Failure modes and how each surfaces

**Decision:** Three classes of failure, each handled distinctly:

| Failure | What happens | UI sees |
|---|---|---|
| Anthropic API down / 5xx | Retry once with 1s backoff. If still failing, return 502. | Toast: "AI explanation unavailable; please retry." Cleared button still works. |
| Schema violation (model returned non-conforming output) | Log raw response with `langsmith_run_id`; return 502. **Never** surface raw model output to UI. | Same toast as above. |
| Refusal (`stop_reason: refusal`) | Persist the refusal category to `suspicious_triage` for debugging; return a friendly message: "This booking couldn't be classified automatically; please review manually." | Friendly message, not raw refusal. |
| LangSmith down | Log and continue. **Never** block the user response on trace shipping. | No UI impact. |

**Rationale:** The LLM is augmenting human review, not replacing it. If the AI fails, the existing manual workflow still works perfectly. We never want a flaky LangSmith or Anthropic outage to break the suspicious-bookings page.

### D13: System prompt design for prompt caching

**Decision:** Construct the system prompt to hit ≥4096 tokens (Haiku's minimum cacheable prefix) by including:
1. The 3-class classification rubric with explicit "classify as X when Y" criteria
2. The full detection-signal taxonomy (every signal the detector emits, with a 1-sentence definition)
3. The output schema (auto-derived by SDK, but include human-readable comments)
4. 3-5 few-shot examples covering edge cases (refund, comp, new-customer-with-zero-tip, actual-fraud, …)
5. Owner-comp policy reminder (don't flag owner customers — they're filtered upstream, but worth reminding the model)

Cache marker (`cache_control: ephemeral`) on the last system block. Per-request user message contains only the specific booking data (signals fired, customer name, service, gross amount, timing details).

**Rationale:** Caching gives ~92% cost reduction on cached tokens AND ~1 sec latency improvement (Haiku skips re-processing the prefix). System prompts under 4096 tokens silently no-op — better to design for the minimum from day 1 than discover the cache isn't working in production.

### D14: Confidence is calibrated, not just a model self-report

**Decision:** The `confidence` field is part of the structured output, but we explicitly instruct the model in the system prompt: *"Confidence MUST reflect how much evidence supports the classification; if signals are weak or contradictory, set confidence < 0.5 and choose `needs_review`."* Downstream UI uses confidence to render visual emphasis (high-confidence fraud → red border; low-confidence anything → muted).

**Rationale:** Without explicit instruction, models tend to over-confident outputs. Calibration via prompt is the cheapest fix; eval data will reveal whether it's enough.

**Alternatives considered:**
- *Compute confidence post-hoc from logprobs*: Anthropic API doesn't expose token logprobs. Not an option.
- *Multi-sample voting (run 3 times, count agreement)*: 3× cost for marginal calibration improvement. Skip in V1.

### D15: Eval set bootstrap from existing Cleared bookings

**Decision:** On first deploy, backfill the eval set from existing cleared suspicious bookings that have a `cleared_note` (those notes are the human-generated reason — a strong signal of correct classification). One-time script reads `suspicious_booking_clearance.note`, runs the V1 prompt against the historical bookings, ships the (input, output, owner-reasoning) tuples to LangSmith as a labeled dataset.

**Rationale:** Day-1 eval data lets us validate Haiku before owners ever click Explain. Without bootstrapping we'd wait weeks for organic labels.

**Alternatives considered:**
- *Skip bootstrap, wait for organic labels*: slower feedback loop; can't make a Haiku-vs-Sonnet decision before launch.

## Risks / Trade-offs

- **Cost overrun.** *Mitigation:* per-tenant feature flag (D10), cache by `(booking_id, prompt_version)` (D5), prompt caching (D13). Worst case at 1000 triages/day on Haiku = ~$2/day. Monitor via LangSmith dashboard; alert if daily spend > $10.

- **Hallucinated classification.** *Mitigation:* structured outputs with enum guardrail (D3) means classification is always one of three valid values; `confidence` field self-reports uncertainty (D14); human-in-the-loop (D4) means owner always reviews before acting; eval loop (D9) catches systematic errors.

- **Prompt regression.** *Mitigation:* prompt versioning (D7) + LangSmith regression-test dataset (D9). New `PROMPT_VERSION` runs against historical labeled set before merge; PR review can see the metric diff.

- **LangSmith outage during a deploy.** *Mitigation:* trace shipping is async and non-blocking (D6, D12). User response never waits on LangSmith. Worst case: lose a few hours of trace data; metrics dashboard has a gap.

- **Anthropic outage.** *Mitigation:* feature flag kill switch (D10). Existing manual Clear / Undo workflow keeps working with zero degradation; the only loss is the AI explanation.

- **Refusal on legit requests.** *Mitigation:* friendly fallback message (D12); refusals counted in LangSmith dashboard so we can spot if a prompt change is causing more refusals.

- **Eval set bias.** Owners may systematically ignore certain types of suspicious bookings, so the eval set is biased toward bookings owners actually reviewed. *Mitigation:* monitor for coverage gaps (classifications with no labels after N weeks); occasionally seed the eval set with high-confidence-but-unreviewed bookings.

- **First-call latency on cache miss.** Haiku's first-call latency is ~1-2 sec; with cache write premium it's slightly more on cache-miss calls. *Mitigation:* streaming (D8) so user sees text appearing immediately; cache hit (the common case after first use) is ~300ms TTFT.

## Migration Plan

No staging environment exists for salaryReview — only local (laptop docker-compose) and prod. Migration goes local → prod-with-flag-off → prod-with-flag-on for the canary owner.

1. **Phase 1 — local validation.** `docker compose up -d --build` on the laptop. Flyway V21 applies; new table created empty. `./mvnw test` and `npx tsc --noEmit` pass. With `AI_TRIAGE_ENABLED=true` locally, click "Explain" on a flagged booking from real Square data; verify the streamed response, the LangSmith trace, the cache-hit on second click, and the failure-mode behaviors (invalid API key → friendly 502; LangSmith down → triage still succeeds).
2. **Phase 2 — eval bootstrap (local).** Run the one-time bootstrap script (D15) against the local dev DB. Verify the LangSmith dataset populates correctly. If Haiku's accuracy on the bootstrap set looks viable (≥ 75% as a rough gate), proceed; if not, prompt-engineering pass before exposing the feature to prod.
3. **Phase 3 — prod deploy with flag off.** Merge to master; CI deploys. `AI_TRIAGE_ENABLED=false` in prod env. No user-visible change. Confirms the migration runs cleanly and the dormant code doesn't break anything else.
4. **Phase 4 — `olexandr.kara2` canary in prod.** Set `AI_TRIAGE_ENABLED=true`. Click "Explain" on real prod flagged bookings; sanity-check traces in LangSmith. Look for refusals, schema violations, weird outputs.
5. **Phase 5 — eval review.** After ~50 real triages with owner Clear/Undo feedback in prod, review the LangSmith confusion matrix. Decision point: is Haiku good enough? If accuracy ≥ 90%, ship as the default for new tenants. If 75-90%, prompt-engineering pass. If < 75%, escalate to Sonnet 4.6 and re-run.
6. **Phase 6 — broader rollout.** Per-tenant enable as new tenants onboard. No big-bang.

**Rollback:** `AI_TRIAGE_ENABLED=false` per tenant. Table stays (no destructive rollback needed). Code can stay on master.

## Open Questions

1. **Manager visibility.** Spec gates new endpoints to OWNER + MANAGER (same as existing `/api/suspicious/**`). Should managers also see the *eval feedback* widget, or only owners? *Initial answer:* both, since both can already Clear/Undo. Revisit if managers spam thumbs feedback in ways that pollute the eval set.
2. **LangSmith trace URL exposure.** Should we render a "View trace in LangSmith" link in the UI for owners? *Initial answer:* no, but log the trace URL in the backend so we can grab it from logs when debugging.
3. **Per-tenant prompt customization.** Some tenants might want different rubrics (e.g., "we don't care about zero-tip bookings"). *Initial answer:* not in V1. Revisit when a second tenant onboards.
4. **Rate-limit policy.** How many triages per owner per minute? *Initial answer:* none in V1 — owner-driven trigger + cache means abuse vector is small. Add a Spring `@RateLimiter` if cost monitoring shows spikes.
