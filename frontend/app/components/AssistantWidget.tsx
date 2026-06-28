'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from './Spinner';
import type { RagCitation, Role } from '../lib/types';

type Msg = {
  id: number;
  who: 'user' | 'assistant';
  text: string;
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
  const bottomRef = useRef<HTMLDivElement>(null);
  const pathname = usePathname();

  // Re-check the role on mount and on every client-side navigation until it resolves to a concrete
  // role. The widget lives in the root layout, which does NOT remount on client navigation — so after
  // signing in (a router.replace, not a reload) this is what makes it appear without a manual refresh.
  useEffect(() => {
    if (role === 'OWNER' || role === 'MANAGER' || role === 'PROVIDER') return;
    let cancelled = false;
    api.getMe()
      .then((me) => { if (!cancelled) setRole(me.role); })
      .catch(() => { if (!cancelled) setRole(null); });
    return () => { cancelled = true; };
  }, [pathname, role]);

  useEffect(() => {
    if (open) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, open]);

  if (role !== 'OWNER' && role !== 'MANAGER') return null;

  function patch(id: number, fn: (m: Msg) => Msg) {
    setMessages((ms) => ms.map((m) => (m.id === id ? fn(m) : m)));
  }

  async function send() {
    const q = input.trim();
    if (!q || busy) return;
    setInput('');
    setBusy(true);
    const aid = nextId();
    setMessages((ms) => [
      ...ms,
      { id: nextId(), who: 'user', text: q },
      { id: aid, who: 'assistant', text: '', streaming: true },
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
              <p className="mt-6 text-center text-xs text-[var(--muted)]">
                Ask about salon policies, pricing, or procedures. Answers come only from the knowledge base.
              </p>
            ) : null}
            {messages.map((m) => (
              <div key={m.id} className={m.who === 'user' ? 'text-right' : ''}>
                <div
                  className={`inline-block max-w-[90%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm ${
                    m.who === 'user'
                      ? 'bg-[var(--ink)] text-[var(--paper)]'
                      : 'bg-[var(--paper-2)] text-[var(--ink)]'
                  }`}
                >
                  {m.text || (m.streaming ? <Spinner className="h-4 w-4 text-[var(--muted)]" /> : '')}
                </div>
                {m.who === 'assistant' && !m.streaming ? (
                  <div className="mt-1">
                    {m.citations && m.citations.length > 0 ? (
                      <ul className="space-y-0.5">
                        {m.citations.map((c, i) => (
                          <li key={i} className="text-[11px] text-[var(--muted)]">
                            <span className="font-medium text-[var(--accent-ink)]">{c.documentTitle}</span>
                            {c.citedText ? <span> — “{c.citedText}”</span> : null}
                          </li>
                        ))}
                      </ul>
                    ) : null}
                    {m.answered && m.runId ? (
                      <div className="mt-1 flex items-center gap-2 text-xs text-[var(--muted)]">
                        <button onClick={() => rate(m, true)} disabled={m.rated} className="disabled:opacity-50">👍</button>
                        <button onClick={() => rate(m, false)} disabled={m.rated} className="disabled:opacity-50">👎</button>
                        {m.rated ? <span>thanks</span> : null}
                      </div>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ))}
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

function SparkleIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2l1.6 4.8L18.4 8.4 13.6 10 12 14.8 10.4 10 5.6 8.4l4.8-1.6L12 2zM5 14l.9 2.6L8.5 17.5 5.9 18.4 5 21l-.9-2.6L1.5 17.5 4.1 16.6 5 14zM18 13l1.1 3.1L22.2 17.2l-3.1 1.1L18 21.4l-1.1-3.1L13.8 17.2l3.1-1.1L18 13z" />
    </svg>
  );
}
