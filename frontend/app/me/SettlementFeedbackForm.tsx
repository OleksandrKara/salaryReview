'use client';

import { useRouter } from 'next/navigation';
import { useState, useTransition } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import type { Feedback, FeedbackStatus } from '../lib/types';

// Provider approves or requests a correction for ONE period (1-15 or 16-end). The backend scopes the
// submission to the authenticated provider; we pass year/month/half.
export default function SettlementFeedbackForm({
  year,
  month,
  half,
  current,
}: {
  year: number;
  month: number;
  half: 'FIRST' | 'SECOND';
  current: Feedback | null;
}) {
  const router = useRouter();
  const [comment, setComment] = useState(current?.comment ?? '');
  const [saving, setSaving] = useState(false);
  const [pending, startTransition] = useTransition();
  const busy = saving || pending;
  const [error, setError] = useState('');

  async function submit(status: FeedbackStatus) {
    setError('');
    setSaving(true);
    try {
      await api.submitFeedback(year, month, half, status, comment);
      startTransition(() => router.refresh());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to submit');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mt-3 border-t border-zinc-200 pt-3">
      <div className="mb-2 flex items-center gap-2 text-xs">
        <span className="font-medium text-zinc-600">Your response</span>
        {current?.status === 'APPROVED' && (
          <span className="rounded bg-green-50 px-1.5 py-0.5 font-medium text-green-700 ring-1 ring-green-300">approved</span>
        )}
        {current?.status === 'CHANGES_REQUESTED' && (
          <span className="rounded bg-red-50 px-1.5 py-0.5 font-medium text-red-700 ring-1 ring-red-300">changes requested</span>
        )}
      </div>
      <textarea
        data-testid="feedback-comment"
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        placeholder="Optional note (helpful when requesting a correction)"
        rows={2}
        className="w-full rounded border border-zinc-300 px-2 py-1.5 text-sm"
      />
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
      <div className="mt-2 flex gap-2">
        <button data-testid="feedback-approve-btn" onClick={() => submit('APPROVED')} disabled={busy}
          className="inline-flex items-center gap-1.5 rounded bg-green-600 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50">
          {busy && <Spinner className="h-3.5 w-3.5" />}Approve
        </button>
        <button data-testid="feedback-request-correction-btn" onClick={() => submit('CHANGES_REQUESTED')} disabled={busy}
          className="rounded bg-white px-3 py-1.5 text-xs font-medium text-red-600 ring-1 ring-red-300 disabled:opacity-50">
          Request correction
        </button>
      </div>
    </div>
  );
}
