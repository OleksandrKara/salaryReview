## ADDED Requirements

### Requirement: The AI Advisor is triggered explicitly by the owner, never automatically
The system SHALL only call the LLM in response to an explicit owner action (an "Analyze SEO"
button, or an explicit "Analyze again" override), and SHALL NOT call it on page load, on a
schedule, or as a side effect of any other action.

#### Scenario: Owner opens the SEO tab
- **WHEN** an owner navigates to the Advisor sub-view
- **THEN** the last persisted analysis (if any) is shown from storage, with no new LLM call made

#### Scenario: Owner clicks "Analyze SEO" with unchanged underlying data
- **WHEN** the owner triggers analysis and the current structured SEO snapshot's fingerprint
  matches the most recent persisted analysis's fingerprint
- **THEN** the existing analysis is returned without a new LLM call, unless the owner explicitly
  requests a forced re-analysis

### Requirement: The AI receives a bounded, prioritized structured snapshot, never raw unbounded data
The system SHALL construct the LLM's input through an aggregation → significance-filtering →
ranking → prioritization pipeline with named, documented budget limits per data category, and
SHALL NOT send unbounded raw Search Console/Analytics rows to the model.

#### Scenario: A business has more queries than the context budget
- **WHEN** a business has more tracked/eligible queries than the configured top-N budget for
  queries
- **THEN** the snapshot includes the highest-priority N by the documented ranking (not an
  arbitrary or alphabetical truncation), and lower-priority data is summarized rather than dropped
  silently

#### Scenario: Reconstructing what the AI saw
- **WHEN** an owner opens a historical analysis
- **THEN** the exact structured snapshot used to generate that analysis is available for
  inspection, unchanged by any later data sync

### Requirement: Every AI analysis is persisted and never overwritten
The system SHALL create a new `seo_analysis` row for every analysis performed (not update an
existing row), retaining the snapshot, the response, the model, the prompt version, and the
language used.

#### Scenario: Owner requests a second analysis a month later
- **WHEN** a new analysis is generated for the same business
- **THEN** the previous month's analysis remains unchanged and independently viewable in history

#### Scenario: Owner reviews analysis history
- **WHEN** an owner opens the history list
- **THEN** each entry shows its date, language, and headline status without needing to open it,
  and opening it shows the full recommendations exactly as originally generated

### Requirement: AI recommendations are actionable and scored, not a data summary
The system SHALL produce, for each recommendation, a priority, the action, why it matters now
(relative to other candidate actions), supporting evidence, expected impact, effort, confidence,
a suggested implementation, and the relevant page or keyword — not a restatement of raw metrics.

#### Scenario: Two candidate actions compete for priority
- **WHEN** the AI has both a content-improvement and a technical-performance candidate
  recommendation
- **THEN** the higher-priority recommendation's rationale explicitly addresses why it matters more
  right now, not just that it is a generally good SEO practice

### Requirement: The AI Advisor responds in the business's current UI language
The system SHALL generate its response in the language the requesting owner's account is
currently configured for (English or Russian), using the same language-resolution mechanism
already used by the app's other AI-powered features, and SHALL NOT require a separate
SEO-specific language setting.

#### Scenario: Owner's account is set to Russian
- **WHEN** an owner whose account language is Russian triggers an analysis
- **THEN** the recommendations are returned in Russian, and the underlying stored data model is
  unchanged (only presentation/response language differs)

### Requirement: LLM failures degrade gracefully, never as an unhandled error
The system SHALL handle a policy refusal from the model with a graceful, bilingual fallback
response, and SHALL handle any other LLM call failure by surfacing a clear, user-facing error state
rather than a raw exception or a silent hang.

#### Scenario: The model refuses to respond
- **WHEN** the LLM's response indicates a policy refusal
- **THEN** the owner sees a graceful fallback message in their configured language, not a broken
  UI state

#### Scenario: The LLM call fails outright
- **WHEN** the underlying API call throws (timeout, outage, invalid response)
- **THEN** the owner sees a clear "analysis failed, try again" state, and no partial or corrupted
  `seo_analysis` row is persisted
