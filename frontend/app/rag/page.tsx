'use client';

import Link from 'next/link';
import { useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import type { RagAnswer } from '../lib/types';

// Manager/owner knowledge assistant. Ask a question; get an answer grounded in the uploaded
// document corpus, with citations. Non-streaming (a spinner during the call) — native citations
// need the full response to resolve, same reasoning as the triage feature dropping streaming.
export default function RagChatPage() {
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState<RagAnswer | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rated, setRated] = useState<boolean | null>(null);

  async function ask(e: React.FormEvent) {
    e.preventDefault();
    const q = question.trim();
    if (!q || busy) return;
    setBusy(true);
    setError(null);
    setAnswer(null);
    setRated(null);
    try {
      setAnswer(await api.askRag(q));
    } catch {
      setError('The assistant is unavailable right now. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function rate(helpful: boolean) {
    if (!answer?.traceRunId || rated !== null) return;
    setRated(helpful);
    try {
      await api.submitRagFeedback(answer.traceRunId, helpful);
    } catch {
      /* feedback is best-effort */
    }
  }

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <div className="mb-6 flex items-center justify-between">
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <h1 className="text-lg font-semibold">Knowledge assistant</h1>
      <p className="mt-1 text-sm text-zinc-500">
        Ask about salon policies, procedures, or pricing. Answers come only from the uploaded
        documents and cite their source.
      </p>

      <form onSubmit={ask} className="mt-5">
        <textarea
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          rows={3}
          placeholder="e.g. What is our no-show policy?"
          className="w-full resize-none rounded-lg px-3 py-2 text-sm ring-1 ring-zinc-200 focus:outline-none focus:ring-2 focus:ring-zinc-400"
        />
        <button
          type="submit"
          disabled={busy || !question.trim()}
          className="mt-2 inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
        >
          {busy ? <Spinner className="h-4 w-4 text-white" /> : null}
          {busy ? 'Thinking…' : 'Ask'}
        </button>
      </form>

      {error ? (
        <p className="mt-6 rounded-lg px-4 py-3 text-sm text-red-600 ring-1 ring-red-200">{error}</p>
      ) : null}

      {answer ? (
        <section className="mt-6 rounded-lg p-4 ring-1 ring-zinc-200">
          <p className="whitespace-pre-wrap text-sm text-zinc-800">{answer.answer}</p>

          {answer.citations.length > 0 ? (
            <div className="mt-4 border-t border-zinc-100 pt-3">
              <h2 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Sources</h2>
              <ul className="mt-2 space-y-2">
                {answer.citations.map((c, i) => (
                  <li key={i} className="text-xs text-zinc-600">
                    <span className="font-medium text-zinc-800">{c.documentTitle}</span>
                    {c.citedText ? <span className="text-zinc-500"> — “{c.citedText}”</span> : null}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {answer.answered && answer.traceRunId ? (
            <div className="mt-4 flex items-center gap-2 border-t border-zinc-100 pt-3">
              <span className="text-xs text-zinc-500">Was this helpful?</span>
              <button
                onClick={() => rate(true)}
                disabled={rated !== null}
                className={`rounded px-2 py-1 text-xs ring-1 ring-zinc-200 disabled:opacity-50 ${rated === true ? 'bg-green-50 text-green-700' : ''}`}
              >
                👍 Yes
              </button>
              <button
                onClick={() => rate(false)}
                disabled={rated !== null}
                className={`rounded px-2 py-1 text-xs ring-1 ring-zinc-200 disabled:opacity-50 ${rated === false ? 'bg-red-50 text-red-700' : ''}`}
              >
                👎 No
              </button>
              {rated !== null ? <span className="text-xs text-zinc-400">Thanks for the feedback.</span> : null}
            </div>
          ) : null}
        </section>
      ) : null}
    </main>
  );
}
