## 1. Backend — streaming endpoint

- [x] 1.1 Refactor `RagAnswerService` so prompt/`document`-block assembly + citation mapping are reusable by both the buffered and streaming paths (extract a helper that builds `MessageCreateParams` and one that maps citation blocks → `Citation`s)
- [x] 1.2 Add `RagAnswerService.answerStream(question, sink)` — retrieval + active config; empty context → emit the "don't know" answer + done; else Anthropic `createStreaming(...)`, forward text deltas to the sink, resolve citations from the final message, record the two-span LangSmith trace, return the trace run id. Verify the streaming binding against the SDK jar.
- [x] 1.3 `RagController` `POST /api/rag/ask/stream` producing `text/event-stream` — drive an emitter on an async worker; SSE events `token` (text), `citations` (sources), `done` (`{traceRunId}`), `error`; close the upstream stream in a finally. (No `SecurityConfig` change — `/api/rag/**` already gates OWNER+MANAGER.)

## 2. Frontend — proxies

- [x] 2.1 `app/api/me/route.ts` — GET proxy → backend `/api/me` (for the widget's role self-gate)
- [x] 2.2 `app/api/rag/ask/stream/route.ts` — POST proxy that forwards the session cookie and pipes the backend `ReadableStream` through unbuffered (`text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`)

## 3. Frontend — widget

- [x] 3.1 `app/lib/api.ts` — a streaming `askRagStream(question, { onToken, onCitations, onDone, onError })` helper (fetch + `ReadableStream` reader + SSE line parsing); a `getMe()` role fetch
- [x] 3.2 `AssistantWidget` (client) — floating bottom-right button + chat panel; self-gates via `/api/me` (render only for OWNER/MANAGER; nothing on 401/PROVIDER); streams tokens into the answer bubble; shows citations + thumbs feedback on `done`; OWNER sees an "Admin" link to `/rag/admin`
- [x] 3.3 Mount `<AssistantWidget />` once in `app/layout.tsx`

## 4. Frontend — declutter

- [x] 4.1 `AdminMenu` — remove the `/rag` ("Assistant") and `/rag/admin` ("Assistant admin") entries
- [x] 4.2 Delete `app/rag/page.tsx` (replaced by the widget); keep `/rag/admin` + its routes

## 5. Tests & verification

- [x] 5.1 Backend unit/slice: the streaming path emits ordered `token…` then `citations` then `done` for a grounded question, and an empty-context "don't know" with no citations (faked `AnthropicClient`); reuse of the shared assembly verified
- [x] 5.2 `MockMvc`: `POST /api/rag/ask/stream` returns `text/event-stream`; role gating is via `SecurityConfig` (PROVIDER → 403, transitive per repo convention)
- [x] 5.3 Frontend `tsc` + `eslint` + `next build` clean
- [ ] 5.4 Manual at `localhost:3000` as `olexandr.kara2`: floating button shows (owner); ask → answer streams token-by-token; citations + thumbs on completion; Admin link opens `/rag/admin`; menu has no Assistant/Assistant-admin items; a provider sees no widget. Confirm tokens arrive incrementally (not one chunk) and no Square calls.
