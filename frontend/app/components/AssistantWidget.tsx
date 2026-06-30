'use client';

import dynamic from 'next/dynamic';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from './Spinner';
import type { KbRequestTarget, Language, RagCitation, Role, StarterSuggestions } from '../lib/types';

// @uiw/react-md-editor's Markdown renderer (already a dependency) — client-only since it touches DOM.
const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

type Msg = {
  id: number;
  who: 'user' | 'assistant';
  text: string;
  question?: string; // the asked question (assistant turns) — used when filing a knowledge-gap request
  citations?: RagCitation[];
  runId?: string | null;
  answered?: boolean;
  streaming?: boolean;
  rated?: boolean;
};

let counter = 0;
const nextId = () => ++counter;

// Floating "ask the assistant" widget. Renders only for OWNER/MANAGER (self-gated via /api/me); for
// PROVIDER / unauthenticated it renders nothing. Streams answers token-by-token over SSE.
export default function AssistantWidget() {
  const [role, setRole] = useState<Role | null | undefined>(undefined);
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [suggestEnabled, setSuggestEnabled] = useState(false);
  const [suggestions, setSuggestions] = useState<StarterSuggestions | null>(null);
  const langRef = useRef<Language | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const pathname = usePathname();

  // Re-read the principal on mount and on every client-side navigation. The widget lives in the root
  // layout, which does NOT remount on client navigation — so this is what makes it appear right after
  // sign-in and pick up a language change made from the menu. When the language changes, drop any
  // cached suggestions so they re-fetch in the new language.
  useEffect(() => {
    let cancelled = false;
    api.getMe()
      .then((me) => {
        if (cancelled) return;
        setRole(me.role);
        setSuggestEnabled(me.features.ragSuggestionsEnabled);
        if (langRef.current !== null && langRef.current !== me.preferredLanguage) {
          setSuggestions(null); // language changed — re-fetch chips in the new language
        }
        langRef.current = me.preferredLanguage;
      })
      .catch(() => { if (!cancelled) setRole(null); });
    return () => { cancelled = true; };
  }, [pathname]);

  // Fetch grounded starter prompts the first time the panel is opened (server-cached per language, so
  // no LLM call on a normal open). Re-fetches after a language change cleared them above.
  useEffect(() => {
    if (!open || !suggestEnabled || suggestions !== null) return;
    let cancelled = false;
    api.getRagSuggestions()
      .then((s) => { if (!cancelled) setSuggestions(s); })
      .catch(() => { if (!cancelled) setSuggestions({ topics: [] }); });
    return () => { cancelled = true; };
  }, [open, suggestEnabled, suggestions]);

  // Loading is derived, not state — true while the first fetch is in flight.
  const suggestLoading = open && suggestEnabled && suggestions === null;

  useEffect(() => {
    if (open) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, open]);

  if (role !== 'OWNER' && role !== 'MANAGER') return null;

  function patch(id: number, fn: (m: Msg) => Msg) {
    setMessages((ms) => ms.map((m) => (m.id === id ? fn(m) : m)));
  }

  async function send(textOverride?: string) {
    const q = (textOverride ?? input).trim();
    if (!q || busy) return;
    setInput('');
    setBusy(true);
    const aid = nextId();
    setMessages((ms) => [
      ...ms,
      { id: nextId(), who: 'user', text: q },
      { id: aid, who: 'assistant', text: '', question: q, streaming: true },
    ]);
    await api.askRagStream(q, {
      onToken: (t) => patch(aid, (m) => ({ ...m, text: m.text + t })),
      onCitations: (c) => patch(aid, (m) => ({ ...m, citations: c })),
      onDone: (d) => {
        patch(aid, (m) => ({ ...m, streaming: false, runId: d.traceRunId, answered: d.answered }));
        setBusy(false);
      },
      onError: (msg) => {
        patch(aid, (m) => ({ ...m, streaming: false, text: m.text || msg }));
        setBusy(false);
      },
    });
  }

  async function rate(m: Msg, helpful: boolean) {
    if (!m.runId || m.rated) return;
    patch(m.id, (x) => ({ ...x, rated: true }));
    try {
      await api.submitRagFeedback(m.runId, helpful);
    } catch {
      /* best-effort */
    }
  }

  return (
    <>
      {/* Floating button */}
      {!open ? (
        <button
          onClick={() => setOpen(true)}
          aria-label="Ask the assistant"
          className="fixed bottom-6 right-6 z-50 flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] text-[var(--paper)] shadow-[0_10px_30px_-8px_rgba(184,151,90,0.7)] ring-1 ring-[var(--accent)]/40 transition hover:scale-105"
        >
          <SparkleIcon className="h-6 w-6" />
        </button>
      ) : null}

      {/* Panel */}
      {open ? (
        <div className="fixed bottom-6 right-6 z-50 flex h-[32rem] w-[22rem] max-w-[calc(100vw-2rem)] flex-col overflow-hidden rounded-2xl bg-[var(--paper)] shadow-2xl ring-1 ring-[var(--line)]">
          <div className="flex items-center justify-between bg-[var(--ink)] px-4 py-3 text-[var(--paper)]">
            <span className="flex items-center gap-2 text-base tracking-wide" style={{ fontFamily: 'var(--serif)' }}>
              <SparkleIcon className="h-4 w-4 text-[var(--accent)]" /> Assistant
            </span>
            <span className="flex items-center gap-3">
              {role === 'OWNER' ? (
                <Link href="/rag/admin" className="text-xs text-[var(--accent)] hover:opacity-80">Admin</Link>
              ) : null}
              <button onClick={() => setOpen(false)} aria-label="Close" className="text-[var(--paper)]/70 hover:text-[var(--paper)]">✕</button>
            </span>
          </div>

          <div className="flex-1 space-y-3 overflow-y-auto px-4 py-3">
            {messages.length === 0 ? (
              <div className="mt-3 space-y-4">
                <p className="text-center text-xs text-[var(--muted)]">
                  Ask about salon policies, pricing, or procedures — answers come only from your knowledge base.
                </p>
                {suggestLoading ? (
                  <div className="flex justify-center"><Spinner className="h-4 w-4 text-[var(--muted)]" /></div>
                ) : null}
                {suggestions?.topics.map((t) => (
                  <div key={t.label}>
                    <div className="mb-1.5 text-[10px] font-medium uppercase tracking-wide text-[var(--accent-ink)]">
                      {t.label}
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      {t.questions.map((q) => (
                        <button
                          key={q}
                          onClick={() => send(q)}
                          className="rounded-full bg-[var(--paper-2)] px-3 py-1.5 text-left text-xs text-[var(--ink)] ring-1 ring-[var(--line)] transition hover:ring-[var(--accent)]"
                        >
                          {q}
                        </button>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            ) : null}
            {messages.map((m) =>
              m.who === 'user' ? (
                <div key={m.id} className="flex justify-end">
                  <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-sm bg-[var(--ink)] px-3.5 py-2 text-sm text-[var(--paper)]">
                    {m.text}
                  </div>
                </div>
              ) : (
                <AssistantMessage key={m.id} m={m} onRate={rate} />
              ),
            )}
            <div ref={bottomRef} />
          </div>

          <form
            onSubmit={(e) => { e.preventDefault(); send(); }}
            className="flex items-center gap-2 border-t border-[var(--line)] p-3"
          >
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="Ask a question…"
              className="flex-1 rounded-full bg-white px-3 py-2 text-sm text-[var(--ink)] ring-1 ring-[var(--line)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]"
            />
            <button
              type="submit"
              disabled={busy || !input.trim()}
              className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] text-[var(--paper)] disabled:opacity-40"
              aria-label="Send"
            >
              {busy ? <Spinner className="h-4 w-4 text-[var(--paper)]" /> : '➤'}
            </button>
          </form>
        </div>
      ) : null}
    </>
  );
}

// An assistant turn: sparkle marker + a clean white "prose card" rendering Markdown (headings, lists,
// bold, code), a streaming cursor while it arrives, then a Copy button, Sources, and feedback.
function AssistantMessage({ m, onRate }: { m: Msg; onRate: (m: Msg, helpful: boolean) => void }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(m.text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard blocked — ignore */
    }
  }

  const empty = !m.text && m.streaming;

  return (
    <div className="flex gap-2">
      <span className="mt-1.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] text-[var(--paper)]">
        <SparkleIcon className="h-3.5 w-3.5" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="rounded-2xl rounded-tl-sm bg-[var(--white)] px-3.5 py-2.5 ring-1 ring-[var(--line)]">
          {empty ? (
            <div className="flex items-center gap-2 text-[var(--muted)]">
              <Spinner className="h-4 w-4" /> <span className="text-xs">Thinking…</span>
            </div>
          ) : (
            <div data-color-mode="light" className="assistant-md text-sm">
              <Markdown source={m.text} style={{ background: 'transparent', fontSize: '0.875rem' }} />
              {m.streaming ? (
                <span className="ml-0.5 inline-block h-3.5 w-1.5 translate-y-0.5 animate-pulse rounded-sm bg-[var(--accent)]" />
              ) : null}
            </div>
          )}
        </div>

        {!m.streaming ? (
          <>
            {m.citations && m.citations.length > 0 ? (
              <div className="mt-2">
                <div className="mb-1 flex items-center gap-1 text-[10px] font-medium uppercase tracking-wide text-[var(--accent-ink)]">
                  <BookIcon className="h-3 w-3" /> Sources
                </div>
                <ul className="space-y-1">
                  {m.citations.map((c, i) => (
                    <li key={i} className="rounded-lg bg-[var(--paper-2)] px-2.5 py-1.5 text-[11px] text-[var(--muted)]">
                      <div className="font-medium text-[var(--ink)]">{c.documentTitle}</div>
                      {c.citedText ? <div className="mt-0.5 line-clamp-2 italic">“{c.citedText}”</div> : null}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {m.text ? (
              <div className="mt-1.5 flex items-center gap-3 text-[var(--muted)]">
                <button
                  onClick={copy}
                  className="inline-flex items-center gap-1 text-[11px] hover:text-[var(--ink)]"
                  aria-label="Copy answer"
                >
                  {copied ? <CheckIcon className="h-3.5 w-3.5 text-green-600" /> : <CopyIcon className="h-3.5 w-3.5" />}
                  {copied ? 'Copied' : 'Copy'}
                </button>
                {m.answered && m.runId ? (
                  <span className="flex items-center gap-2">
                    <button
                      onClick={() => onRate(m, true)}
                      disabled={m.rated}
                      className="hover:text-[var(--ink)] disabled:opacity-50"
                      aria-label="Helpful"
                    >
                      👍
                    </button>
                    <button
                      onClick={() => onRate(m, false)}
                      disabled={m.rated}
                      className="hover:text-[var(--ink)] disabled:opacity-50"
                      aria-label="Not helpful"
                    >
                      👎
                    </button>
                    {m.rated ? <span className="text-[11px]">Thanks</span> : null}
                  </span>
                ) : null}
              </div>
            ) : null}

            {/* Always offer to file a knowledge-gap request — the answer may be missing or incomplete
                even when the corpus returned something (answered=true), which is the common case once
                documents are synced. Emphasized for the explicit "couldn't find that" reply. */}
            {m.question ? <RequestGap question={m.question} emphasize={m.answered === false} /> : null}
          </>
        ) : null}
      </div>
    </div>
  );
}

// A one-click "add to the knowledge base" request the owner triages on /rag/admin. Offered under every
// answer (the answer may be missing/incomplete even when something was retrieved); emphasized when the
// assistant explicitly couldn't find it. Expands to an optional note + a KB/SOP target hint.
function RequestGap({ question, emphasize }: { question: string; emphasize?: boolean }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const [target, setTarget] = useState<KbRequestTarget>('UNSURE');
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle');

  async function submit() {
    setState('sending');
    try {
      await api.createKbRequest({ question, note: note.trim() || null, target });
      setState('sent');
    } catch {
      setState('idle');
    }
  }

  if (state === 'sent') {
    return (
      <p className="mt-2 rounded-lg bg-[var(--paper-2)] px-2.5 py-1.5 text-[11px] text-[var(--accent-ink)]">
        ✅ Sent to the owner to add to the knowledge base.
      </p>
    );
  }

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className={`mt-2 inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[11px] font-medium text-[var(--accent-ink)] ring-1 hover:ring-[var(--accent)] ${
          emphasize ? 'bg-[var(--accent-soft)] ring-[var(--accent)]' : 'bg-[var(--paper-2)] ring-[var(--line)]'
        }`}
      >
        ＋ {emphasize ? 'Request this be added to the knowledge base' : 'Missing or incomplete? Request a KB/SOP update'}
      </button>
    );
  }

  return (
    <div className="mt-2 space-y-2 rounded-lg bg-[var(--paper-2)] p-2.5">
      <p className="text-[11px] text-[var(--muted)]">
        Ask the owner to cover this. <span className="text-[var(--ink)]">“{question}”</span>
      </p>
      <textarea
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder="Add context (optional) — what should the answer say?"
        rows={2}
        className="w-full resize-none rounded bg-white px-2 py-1 text-xs text-[var(--ink)] ring-1 ring-[var(--line)] focus:outline-none focus:ring-2 focus:ring-[var(--accent)]"
      />
      <div className="flex items-center justify-between gap-2">
        <label className="flex items-center gap-1 text-[11px] text-[var(--muted)]">
          Add to
          <select
            value={target}
            onChange={(e) => setTarget(e.target.value as KbRequestTarget)}
            className="rounded bg-white px-1.5 py-1 text-[11px] text-[var(--ink)] ring-1 ring-[var(--line)]"
          >
            <option value="UNSURE">Not sure</option>
            <option value="KB">Knowledge base</option>
            <option value="SOP">SOP</option>
          </select>
        </label>
        <span className="flex items-center gap-1.5">
          <button onClick={() => setOpen(false)} className="rounded px-2 py-1 text-[11px] text-[var(--muted)] hover:text-[var(--ink)]">
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={state === 'sending'}
            className="inline-flex items-center gap-1 rounded-lg bg-gradient-to-br from-[var(--accent)] to-[var(--accent-ink)] px-3 py-1 text-[11px] font-medium text-[var(--paper)] disabled:opacity-50"
          >
            {state === 'sending' ? <Spinner className="h-3 w-3 text-[var(--paper)]" /> : null}
            Send request
          </button>
        </span>
      </div>
    </div>
  );
}

function CopyIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </svg>
  );
}

function CheckIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

function BookIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
    </svg>
  );
}

function SparkleIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2l1.6 4.8L18.4 8.4 13.6 10 12 14.8 10.4 10 5.6 8.4l4.8-1.6L12 2zM5 14l.9 2.6L8.5 17.5 5.9 18.4 5 21l-.9-2.6L1.5 17.5 4.1 16.6 5 14zM18 13l1.1 3.1L22.2 17.2l-3.1 1.1L18 21.4l-1.1-3.1L13.8 17.2l3.1-1.1L18 13z" />
    </svg>
  );
}
