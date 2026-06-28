## Why

The RAG assistant ([[rag-knowledge-assistant]]) is a full-page form at `/rag` reached from a menu item, and answers appear all-at-once after a few seconds. It should feel like a modern AI chat: a floating "magic" button in the bottom-right that opens a chat panel and streams the answer **token by token**. The menu is also getting crowded — the assistant and its admin page don't need their own top-level items.

## What Changes

- **Floating assistant widget (global):** a fixed bottom-right icon/button on every authenticated page that opens a chat panel. Rendered for **OWNER and MANAGER only** (the roles allowed to ask); hidden for PROVIDER and on the unauthenticated landing/login. The widget self-gates by fetching the caller's role.
- **Token-by-token streaming over SSE:** new backend `POST /api/rag/ask/stream` returning `text/event-stream`, built on the Anthropic SDK's streaming API. It emits `token` events (text deltas) as they arrive, then a single `citations` event (resolved from the completed message — native citations need the full response), then `done` (carrying the trace run id for feedback) or `error`. A new streaming proxy route pipes the SSE through without buffering. The panel renders text as it streams and shows citations + a thumbs feedback control when the answer completes.
- **New `/api/me` proxy route** so the client widget can read the caller's role (currently `/api/me` is only fetched server-side).
- **Owner "Admin" affordance moves into the chat:** the widget header shows an **Admin** link to `/rag/admin` for owners — replacing the menu item.
- **Menu decluttered:** remove **Assistant** (`/rag`) and **Assistant admin** (`/rag/admin`) from `AdminMenu`. The standalone `/rag` full-page is removed (the widget replaces it); `/rag/admin` stays, reached from the widget's Admin link.
- **Unchanged:** retrieval, grounding, the all-or-nothing answer model, citations, and feedback semantics — only the delivery (streaming) and surface (widget vs page) change. The existing non-streaming `POST /api/rag/ask` stays for API use; the widget uses the streaming variant.

## Capabilities

### New Capabilities
*(none — this is a delivery/UX change to the existing assistant.)*

### Modified Capabilities
- `rag-knowledge-assistant`: the ask surface becomes a global streaming chat widget (SSE token streaming) instead of a menu-linked full page; an SSE streaming endpoint is added alongside the existing one; the owner admin entry point moves from the menu into the widget. Retrieval/grounding/citations requirements are unchanged.

## Impact

- **Backend**: new `RagController` streaming endpoint `POST /api/rag/ask/stream` (`text/event-stream`) using `AnthropicClient` streaming; reuses `RagRetrievalService`, the active config, the citation-parsing + LangSmith two-span trace from `RagAnswerService` (refactored so the assembly/citation logic is shared between the buffered and streaming paths). No DB change.
- **Frontend**: new client `AssistantWidget` mounted in `app/layout.tsx` (self-gates via `/api/me`); a streaming proxy route under `app/api/rag/ask/stream`; a `/api/me` proxy route; `app/lib/api.ts` streaming helper (fetch + `ReadableStream` reader) and a role fetch; `AdminMenu` loses the two assistant items; the `app/rag/page.tsx` full page is removed; `/rag/admin` kept.
- **Square**: untouched.
- **Out of scope / Non-goals**: **no multi-turn conversation memory** — each question retrieves independently (the panel shows a chat-style thread, but the backend stays single-shot; see Open Questions); no provider access to the assistant; no change to retrieval, grounding, citations, or the admin page itself; no new chat-history persistence.
- **Verification**: backend — the streaming endpoint emits ordered `token…`/`citations`/`done` SSE events for a grounded question and an empty-context "don't know" (faked client), and is OWNER+MANAGER gated (PROVIDER → 403, matching the existing `/api/rag/**` matcher). Frontend `tsc`/`eslint`/`next build` clean. Manual at `localhost:3000` as `olexandr.kara2`: the floating button appears (owner/manager), asking streams the answer token-by-token, citations + thumbs appear on completion, the owner sees an Admin link to `/rag/admin`; a provider sees no widget; the menu no longer lists Assistant / Assistant admin. No Square calls.
