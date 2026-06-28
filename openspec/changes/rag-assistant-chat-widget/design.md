## Context

The RAG assistant ships as a full page at `/rag` (`app/rag/page.tsx`) reached from an `AdminMenu` item, calling the non-streaming `POST /api/rag/ask` which returns the whole `RagAnswer` (answer + native citations) after the model finishes. `RagAnswerService` does retrieval → assembles retrieved chunks as `document` blocks with citations → calls Claude (Haiku 4.5) → parses citation blocks → emits a two-span LangSmith trace. Endpoints are gated OWNER+MANAGER in `SecurityConfig` (`/api/rag/**`). The root layout (`app/layout.tsx`) is a plain server component. There is no `/api/me` proxy. This change is delivery/UX only — no DB change, no change to retrieval or grounding.

## Goals / Non-Goals

**Goals:**
- A modern chat feel: a global floating widget that streams the answer token-by-token.
- Declutter the menu — assistant and its admin entry leave the menu; admin moves into the widget for owners.
- Reuse the existing retrieval/grounding/citation/trace logic; only add a streaming path and a new surface.

**Non-Goals:**
- No multi-turn conversation memory (single-shot per question; see Open Questions).
- No provider access, no change to `/rag/admin`, no chat-history persistence, no change to what makes an answer (same pgvector retrieval + citations).

## Decisions

**D1 — SSE protocol: `token` → `citations` → `done`/`error`.** `POST /api/rag/ask/stream` returns `text/event-stream`. It streams `event: token` (text deltas) live, then `event: citations` (the resolved source list) once the message completes — because native Anthropic citations are only known on the full message — then `event: done` with `{ traceRunId }` (for feedback), or `event: error`. The client renders tokens immediately and reveals citations + the thumbs control on `done`. *Alternative:* stream citation deltas inline — rejected; citations-at-end is simpler and the UX (sources appear under a finished answer) is fine.

**D2 — Build on the Anthropic SDK streaming, share assembly with the buffered path.** Refactor `RagAnswerService` so the prompt/`document`-block assembly and citation mapping are reusable, then add a streaming method that uses the SDK's `createStreaming(...)`, forwards text deltas to the SSE emitter, and on completion parses citations from the final message (same mapping as today). Spring side: a `text/event-stream` controller method driving an emitter on an async worker; close the Anthropic stream in a finally. The non-streaming `/api/rag/ask` stays for API callers.

**D3 — Streaming proxy must not buffer.** The existing `forwardToBackend` buffers (`await res.text()`). The new proxy route (`app/api/rag/ask/stream`) forwards the cookie and returns `new Response(backendRes.body, { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no' } })` — piping the backend's `ReadableStream` straight through. The client reads it with `fetch` + a `ReadableStream` reader (not `EventSource`, which is GET-only; the question is POSTed).

**D4 — Widget self-gates via a new `/api/me` proxy.** `AssistantWidget` is a client component mounted once in the root layout. On mount it fetches `/api/me` (new proxy route → backend `/api/me`); it renders the floating button only when the role is OWNER or MANAGER, and renders nothing on 401 (unauthenticated landing/login) or for PROVIDER. This keeps the server layout free of per-request auth logic and keeps the widget self-contained. *Alternative:* fetch role in the layout via a non-redirecting `getMeOrNull` — rejected; it would add a backend call to every route render (including the static landing) and couple the layout to auth.

**D5 — Admin moves into the widget; menu + `/rag` page removed.** `AdminMenu` drops the `/rag` ("Assistant") and `/rag/admin` ("Assistant admin") entries. The widget header shows an **Admin** link to `/rag/admin` for OWNER only. The standalone `app/rag/page.tsx` is deleted (the widget replaces it); `/rag/admin` and its routes stay.

**D6 — Single-shot, chat-styled.** The panel accumulates a visual thread of question/answer pairs for the session, but each question is an independent retrieval+answer (no conversation context sent to the model, no server memory). This matches the current RAG model and keeps scope contained.

## Risks / Trade-offs

- **Streaming through Next (standalone output) could buffer** → Mitigation: return the backend `ReadableStream` directly with `text/event-stream` + `X-Accel-Buffering: no`; verify tokens arrive incrementally in `next build`/runtime, not in one chunk. This is the riskiest piece and the first thing to test.
- **Anthropic streaming + citations binding** (exact `createStreaming` types, where citation data surfaces on the accumulated message) is the one part not verifiable until build → Mitigation: confirm against the SDK jar at apply time (as done for the citations builder), keep the parsing isolated, and fall back to "stream text, then one buffered citation resolve" which is already the plan.
- **Spring SSE lifecycle** (async emitter, client disconnect, closing the upstream stream) → Mitigation: drive the emitter on a worker, complete/await in finally, handle `IOException` on client close.
- **Widget on every page adds a `/api/me` fetch** → negligible; it's one cached call per load and renders nothing for providers/unauth.

## Migration Plan

1. Backend: refactor `RagAnswerService` to share assembly; add the streaming method + `POST /api/rag/ask/stream` SSE endpoint. (No `SecurityConfig` change — `/api/rag/**` already gates OWNER+MANAGER, and `/api/rag/ask/stream` matches it.)
2. Frontend: add `/api/me` + `/api/rag/ask/stream` proxy routes; `AssistantWidget` + a streaming client helper; mount the widget in `app/layout.tsx`; remove the two `AdminMenu` items and delete `app/rag/page.tsx`.
3. Verify streaming locally, then ship.

**Rollback:** re-add the menu items and the `/rag` page; the widget and streaming endpoint are additive and can be removed independently. The non-streaming endpoint is untouched throughout.

## Open Questions

- **Multi-turn memory?** Default is single-shot (each question independent). If the assistant should remember earlier turns in the session, that's a larger change (send prior turns to the model, decide retrieval-per-turn vs. once) — flag before building if wanted.
- Should the widget also appear for a logged-in owner viewing the public landing page? Default: it renders wherever `/api/me` succeeds, so yes if they're authenticated; acceptable.
- Keep the non-streaming `/api/rag/ask` long-term, or retire it once the widget is the only consumer? Kept for now.
