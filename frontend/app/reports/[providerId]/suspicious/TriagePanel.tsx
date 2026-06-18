'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import type { TriageClassification, TriageResult } from '../../../lib/types';

const CLASS_LABEL: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'Likely legit',
  NEEDS_REVIEW: 'Needs review',
  LIKELY_FRAUD: 'Likely fraud',
};

/** Tailwind classes for the classification chip, by category. Opacity is set inline by confidence. */
const CLASS_RING: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  NEEDS_REVIEW: 'bg-amber-50 text-amber-700 ring-amber-200',
  LIKELY_FRAUD: 'bg-red-50 text-red-700 ring-red-200',
};

type FetchState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'loaded'; result: TriageResult; feedbackSent: boolean }
  | { kind: 'error'; message: string };

/** Inline 3-point sparkle icon (heroicons-style) — universal "AI" visual cue. */
function SparklesIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.75}
      stroke="currentColor"
      className="h-3.5 w-3.5"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456zM16.894 20.567L16.5 21.75l-.394-1.183a2.25 2.25 0 00-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 001.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 001.423 1.423l1.183.394-1.183.394a2.25 2.25 0 00-1.423 1.423z"
      />
    </svg>
  );
}

export default function TriagePanel({
  bookingId,
  year,
  month,
  initialTriage,
  onTriageLoaded,
}: {
  bookingId: string;
  year: number;
  month: number;
  // Cached triage from the server-rendered list — when present, the panel skips the Explain
  // button and renders the result directly so the AI take survives page refreshes.
  initialTriage?: TriageResult | null;
  // Called once a fresh triage is fetched; lets the parent persist it on the item so the panel
  // remounts (e.g. after a Clear moves the row between sections) keep the result visible.
  onTriageLoaded?: (triage: TriageResult) => void;
}) {
  const [state, setState] = useState<FetchState>(
    initialTriage ? { kind: 'loaded', result: initialTriage, feedbackSent: false } : { kind: 'idle' },
  );
  const [showCorrection, setShowCorrection] = useState(false);
  const [copied, setCopied] = useState(false);

  async function runTriage() {
    setState({ kind: 'loading' });
    try {
      const result = await api.requestTriage(bookingId, year, month);
      setState({ kind: 'loaded', result, feedbackSent: false });
      onTriageLoaded?.(result);
    } catch (e) {
      setState({ kind: 'error', message: e instanceof Error ? e.message : 'AI explanation unavailable.' });
    }
  }

  async function sendFeedback(helpful: boolean, corrected: TriageClassification | null) {
    if (state.kind !== 'loaded') return;
    try {
      await api.submitTriageFeedback(bookingId, helpful, corrected);
      setState({ ...state, feedbackSent: true });
      setShowCorrection(false);
    } catch {
      // Silent on feedback failures — the user already got their value from the triage itself.
    }
  }

  async function copyDraft() {
    if (state.kind !== 'loaded') return;
    try {
      await navigator.clipboard.writeText(state.result.draftMessage);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard.writeText can fail in non-secure contexts; ignore.
    }
  }

  if (state.kind === 'idle') {
    return (
      <button
        data-testid={`triage-explain-${bookingId}`}
        onClick={runTriage}
        className="inline-flex items-center gap-1.5 rounded border border-violet-200 bg-gradient-to-r from-white to-violet-50 px-2.5 py-1 text-xs font-medium text-violet-700 shadow-sm hover:from-violet-50 hover:to-violet-100 hover:border-violet-300 focus:outline-none focus:ring-2 focus:ring-violet-200"
        title="Ask the AI to explain this booking"
      >
        <SparklesIcon />
        Explain with AI
      </button>
    );
  }

  if (state.kind === 'loading') {
    return (
      <div
        data-testid={`triage-loading-${bookingId}`}
        className="mt-2 rounded-md bg-zinc-50 px-3 py-2 text-xs text-zinc-500 ring-1 ring-zinc-200"
      >
        Asking the AI to look at this booking…
      </div>
    );
  }

  if (state.kind === 'error') {
    return (
      <div
        data-testid={`triage-error-${bookingId}`}
        className="mt-2 rounded-md bg-red-50 px-3 py-2 text-xs text-red-700 ring-1 ring-red-200"
      >
        <p>{state.message}</p>
        <button onClick={runTriage} className="mt-1 underline">
          Try again
        </button>
      </div>
    );
  }

  // loaded
  const { result, feedbackSent } = state;
  const chipOpacity = 0.4 + Math.min(1, Math.max(0, result.confidence)) * 0.6;

  return (
    <div
      data-testid={`triage-result-${bookingId}`}
      className="mt-2 space-y-2 rounded-md bg-white px-3 py-2 text-xs ring-1 ring-zinc-200"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span
          data-testid={`triage-classification-${bookingId}`}
          className={`rounded px-2 py-0.5 text-[11px] font-medium ring-1 ${CLASS_RING[result.classification]}`}
          style={{ opacity: chipOpacity }}
        >
          {CLASS_LABEL[result.classification]} · {Math.round(result.confidence * 100)}%
        </span>
        <span className="text-[10px] text-zinc-400">
          {result.model} · {result.promptVersion}
        </span>
      </div>

      <p className="text-zinc-700">{result.explanation}</p>

      {result.draftMessage && (
        <div className="rounded bg-zinc-50 px-2 py-1.5 ring-1 ring-zinc-200">
          <div className="mb-1 flex items-center justify-between text-[10px] uppercase tracking-wide text-zinc-400">
            <span>Suggested message</span>
            <button
              data-testid={`triage-copy-${bookingId}`}
              onClick={copyDraft}
              className="rounded border border-zinc-300 bg-white px-2 py-0.5 text-[10px] font-medium text-zinc-600 hover:bg-zinc-50"
            >
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
          <p className="whitespace-pre-wrap text-zinc-700">{result.draftMessage}</p>
        </div>
      )}

      {feedbackSent ? (
        <p data-testid={`triage-feedback-sent-${bookingId}`} className="text-[10px] text-zinc-400">
          Thanks — feedback recorded.
        </p>
      ) : (
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <span className="text-[10px] uppercase tracking-wide text-zinc-400">Was this helpful?</span>
          <button
            data-testid={`triage-thumbs-up-${bookingId}`}
            onClick={() => sendFeedback(true, null)}
            className="rounded border border-zinc-300 bg-white px-2 py-0.5 text-xs hover:bg-emerald-50"
            aria-label="Helpful"
          >
            👍
          </button>
          <button
            data-testid={`triage-thumbs-down-${bookingId}`}
            onClick={() => setShowCorrection((s) => !s)}
            className="rounded border border-zinc-300 bg-white px-2 py-0.5 text-xs hover:bg-red-50"
            aria-label="Not helpful"
          >
            👎
          </button>
          {showCorrection && (
            <div className="flex items-center gap-1">
              <span className="text-[10px] text-zinc-500">Actually:</span>
              {(['LIKELY_LEGIT', 'NEEDS_REVIEW', 'LIKELY_FRAUD'] as TriageClassification[]).map((c) => (
                <button
                  key={c}
                  data-testid={`triage-correction-${c}-${bookingId}`}
                  onClick={() => sendFeedback(false, c)}
                  className={`rounded px-1.5 py-0.5 text-[10px] ring-1 ${CLASS_RING[c]}`}
                >
                  {CLASS_LABEL[c]}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
