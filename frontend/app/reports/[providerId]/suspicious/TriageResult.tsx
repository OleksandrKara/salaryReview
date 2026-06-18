'use client';

import { useState } from 'react';
import type { TriageClassification, TriageResult } from '../../../lib/types';

const LABEL: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'Likely legit',
  NEEDS_REVIEW: 'Needs review',
  LIKELY_FRAUD: 'Likely fraud',
};

const RING: Record<TriageClassification, string> = {
  LIKELY_LEGIT: 'bg-green-50 text-green-700 ring-green-300',
  NEEDS_REVIEW: 'bg-amber-50 text-amber-700 ring-amber-300',
  LIKELY_FRAUD: 'bg-red-50 text-red-700 ring-red-300',
};

/**
 * Loaded-triage panel: classification chip + confidence, plain-English explanation, draft message
 * with a Copy button, and a thumbs-up / thumbs-down feedback widget. Pure presentation — fetching
 * and persisting the triage is handled upstream by {@code SuspiciousList}.
 */
export default function TriageResultDisplay({
  bookingId,
  result,
  feedbackSent,
  onFeedback,
}: {
  bookingId: string;
  result: TriageResult;
  feedbackSent: boolean;
  onFeedback: (helpful: boolean, corrected: TriageClassification | null) => void;
}) {
  const [showCorrection, setShowCorrection] = useState(false);
  const [copied, setCopied] = useState(false);

  async function copyDraft() {
    try {
      await navigator.clipboard.writeText(result.draftMessage);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard.writeText fails in non-secure contexts; ignore.
    }
  }

  const chipOpacity = 0.4 + Math.min(1, Math.max(0, result.confidence)) * 0.6;

  return (
    <div
      data-testid={`triage-result-${bookingId}`}
      className="mt-2 space-y-2 rounded border-l-2 border-amber-300 bg-white px-3 py-2 text-xs ring-1 ring-zinc-200"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span
          data-testid={`triage-classification-${bookingId}`}
          className={`rounded px-2 py-0.5 text-[11px] font-medium ring-1 ${RING[result.classification]}`}
          style={{ opacity: chipOpacity }}
        >
          {LABEL[result.classification]} · {Math.round(result.confidence * 100)}%
        </span>
        <span className="text-[10px] text-zinc-400">
          {result.model} · {result.promptVersion}
        </span>
      </div>

      <p className="text-zinc-700">{result.explanation}</p>

      {result.draftMessage && (
        <div className="rounded bg-white px-2 py-1.5 ring-1 ring-zinc-200">
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
            onClick={() => onFeedback(true, null)}
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
            <div className="flex flex-wrap items-center gap-1">
              <span className="text-[10px] text-zinc-500">Actually:</span>
              {(['LIKELY_LEGIT', 'NEEDS_REVIEW', 'LIKELY_FRAUD'] as TriageClassification[]).map((c) => (
                <button
                  key={c}
                  data-testid={`triage-correction-${c}-${bookingId}`}
                  onClick={() => {
                    onFeedback(false, c);
                    setShowCorrection(false);
                  }}
                  className={`rounded px-1.5 py-0.5 text-[10px] ring-1 ${RING[c]}`}
                >
                  {LABEL[c]}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
