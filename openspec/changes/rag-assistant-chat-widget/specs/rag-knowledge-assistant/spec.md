## ADDED Requirements

### Requirement: Streaming answer delivery over SSE

The assistant SHALL expose a streaming endpoint `POST /api/rag/ask/stream` returning `text/event-stream` that emits the answer token-by-token. It SHALL send `token` events as text arrives, then a single `citations` event once the answer completes (native citations resolve only on the full message), then a terminal `done` event carrying the trace run id (for feedback) — or an `error` event on failure. The same retrieval, grounding, distance-floor "don't know", and two-span LangSmith trace as the non-streaming path SHALL apply. Gating is unchanged: OWNER + MANAGER only.

#### Scenario: Grounded answer streams then cites
- **WHEN** an OWNER or MANAGER asks a question with an answer in the corpus
- **THEN** the response streams `token` events forming the answer, followed by a `citations` event listing the source documents and a `done` event with the trace run id

#### Scenario: No relevant context streams a "don't know"
- **WHEN** no chunk passes the distance floor
- **THEN** the stream emits the "couldn't find that" answer and a `done` event with no citations, making no fabricated citation

#### Scenario: Provider cannot stream
- **WHEN** a PROVIDER calls `POST /api/rag/ask/stream`
- **THEN** the system returns 403

### Requirement: Global floating assistant widget

The assistant SHALL be presented as a fixed bottom-right widget available on every authenticated page, opening a chat panel that streams answers and shows citations and a thumbs feedback control on completion. The widget SHALL be shown only to OWNER and MANAGER (it self-determines the caller's role) and SHALL NOT render for PROVIDER or unauthenticated visitors. For OWNER, the widget SHALL provide an "Admin" link to `/rag/admin`. The previous standalone `/rag` page and the "Assistant" / "Assistant admin" menu items SHALL be removed.

#### Scenario: Owner/manager see the widget; providers don't
- **WHEN** an OWNER or MANAGER loads any authenticated page
- **THEN** the floating assistant button is present
- **WHEN** a PROVIDER (or an unauthenticated visitor) loads a page
- **THEN** no assistant widget renders

#### Scenario: Owner reaches admin from the chat
- **WHEN** an OWNER opens the assistant panel
- **THEN** an "Admin" link to `/rag/admin` is shown; the menu no longer lists "Assistant" or "Assistant admin"

#### Scenario: Asking streams in the panel
- **WHEN** a user submits a question in the panel
- **THEN** the answer appears token-by-token, and on completion the sources and a thumbs up/down control appear
