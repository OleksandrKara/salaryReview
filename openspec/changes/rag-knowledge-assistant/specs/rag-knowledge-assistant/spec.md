## ADDED Requirements

### Requirement: Feature flag gating

The RAG module SHALL be controlled by a `rag.enabled` feature flag (env `RAG_ENABLED`, default `false`). When the flag is off, no RAG beans register and every `/api/rag/**` endpoint SHALL return 404, exactly as `ai.triage` gates the triage module.

#### Scenario: Feature disabled
- **WHEN** `RAG_ENABLED` is `false` and a request hits any `/api/rag/**` endpoint
- **THEN** the system returns 404 and makes no Voyage or Anthropic call

#### Scenario: Feature enabled but Voyage key missing
- **WHEN** `RAG_ENABLED` is `true` but `VOYAGE_API_KEY` is absent
- **THEN** application startup fails fast with a message naming the missing key (mirrors `AnthropicClientConfig`)

### Requirement: Document upload and admin approval gate

An OWNER SHALL be able to upload a document (PDF, Markdown, or plain-text Google-Doc export). The uploaded document SHALL land in status `PENDING` and SHALL NOT be chunked, classified, embedded, or made retrievable until an OWNER explicitly approves it.

#### Scenario: Upload lands pending
- **WHEN** an OWNER uploads a PDF to `POST /api/rag/admin/documents`
- **THEN** a `rag_document` row is created with status `PENDING` and no `rag_chunk` rows exist for it yet

#### Scenario: Manager cannot upload
- **WHEN** a MANAGER or PROVIDER calls `POST /api/rag/admin/documents`
- **THEN** the system returns 403

#### Scenario: Approval starts ingestion
- **WHEN** an OWNER approves a `PENDING` document
- **THEN** the system extracts text, chunks it, runs the PII/relevance gate, embeds the kept chunks, and moves the document to status `INDEXED`

### Requirement: PII and relevance gate runs before embedding

Each chunk SHALL be classified by Claude Haiku 4.5 (structured output: `contains_pii`, `pii_types`, `relevance`, `reason`) BEFORE any embedding call. A chunk flagged as containing PII or as irrelevant SHALL be stored with status `QUARANTINED` and SHALL NOT be sent to Voyage and SHALL NOT be retrievable.

#### Scenario: PII chunk is quarantined and never embedded
- **WHEN** the classifier returns `contains_pii: true` for a chunk
- **THEN** the chunk is stored with status `QUARANTINED`, its embedding column is null, and no Voyage embedding request is made for it

#### Scenario: Clean chunk is embedded and indexed
- **WHEN** the classifier returns `contains_pii: false` and a passing relevance value
- **THEN** the chunk is embedded via Voyage and stored with status `INDEXED` and a populated `vector(1024)` embedding

### Requirement: Grounded question answering with citations

An OWNER or MANAGER SHALL be able to ask a natural-language question. The system SHALL embed the question with the same Voyage model used at ingestion, retrieve the top-k `INDEXED` chunks by cosine distance (subject to a distance floor), pass them to Claude Haiku 4.5 as native `document` content blocks with citations enabled, and return the answer with citations identifying the source documents. When no chunk passes the distance floor, the answer SHALL state that the corpus does not contain the answer.

#### Scenario: Answer cites its sources
- **WHEN** an OWNER asks a question whose answer is in an indexed document
- **THEN** the response contains an answer plus one or more citations each resolving to a `rag_document`

#### Scenario: No relevant context
- **WHEN** no indexed chunk is within the distance floor of the question
- **THEN** the answer states the corpus does not contain the answer and contains no fabricated citation

#### Scenario: Provider cannot ask
- **WHEN** a PROVIDER calls `POST /api/rag/ask`
- **THEN** the system returns 403

### Requirement: Chunk traceability and deletion

Every `rag_chunk` SHALL retain its `rag_document` id, source ordinal, character offsets, and a `content_sha256`. An OWNER SHALL be able to delete a document, which SHALL cascade-delete its chunks (and their vectors) so the content can no longer be retrieved, while a redaction audit record is retained.

#### Scenario: Delete removes retrievability
- **WHEN** an OWNER deletes a document
- **THEN** its `rag_chunk` rows (and embeddings) are removed and subsequent questions cannot surface that content

#### Scenario: Audit survives deletion
- **WHEN** a document is deleted
- **THEN** a redaction audit record (document id, who, when) remains

### Requirement: Versioned agent configuration

The answering agent's configuration (system prompt, model, temperature, k, distance threshold) SHALL be stored in a versioned `rag_agent_config` table. Each answer SHALL record which config version produced it. Updating the config SHALL create a new version rather than mutating the active one.

#### Scenario: Config change creates a new version
- **WHEN** an OWNER updates the system prompt
- **THEN** a new `rag_agent_config` version row is created and prior answers still reference their original version

#### Scenario: Answer records its config version
- **WHEN** a question is answered
- **THEN** the persisted answer/trace records the `rag_agent_config` version used

### Requirement: LangSmith observability and feedback

Each answered question SHALL emit a two-span LangSmith trace (retrieval span: chunk ids + distances; generation span: prompt + cited answer) via the existing `LangSmithTracer`, best-effort and never blocking the response. An OWNER or MANAGER SHALL be able to submit thumbs up/down feedback, shipped as a graded LangSmith run linked to the original trace.

#### Scenario: Trace is emitted
- **WHEN** a question is answered and LangSmith is configured
- **THEN** a retrieval span and a generation span are dispatched asynchronously and any LangSmith failure does not affect the user-facing answer

#### Scenario: Feedback ships as a graded run
- **WHEN** a user submits thumbs up/down on an answer
- **THEN** a LangSmith feedback event is posted against the answer's trace run id
