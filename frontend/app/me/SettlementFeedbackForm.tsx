'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '../lib/api';
import type { FeedbackStatus } from '../lib/types';

// Provider approves their month or requests a correction with a comment. The backend scopes the
// submission to the authenticated provider — the year/month is all we pass.
export default function SettlementFeedbackForm({
  year,
  month,
  currentStatus,
  currentComment,
}: {
  year: number;
  month: number;
  currentStatus: FeedbackStatus | null;
  currentComment: string | null;
}) {
  const router = useRouter();
  const [comment, setComment] = useState(currentComment ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(status: FeedbackStatus) {
    setError('');
    setBusy(true);
    try {
      await api.submitFeedback(year, month, status, comment);
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to submit');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-8 rounded-lg ring-1 ring-zinc-200 p-4">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="text-sm font-semibold">Your response</h2>
        {currentStatus === 'APPROVED' && (
          <span className="rounded bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700 ring-1 ring-green-300">approved</span>
        )}
        {currentStatus === 'CHANGES_REQUESTED' && (
          <span className="rounded bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700 ring-1 ring-red-300">changes requested</span>
        )}
      </div>
      <textarea
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        placeholder="Optional note (required-ish when requesting a correction)"
        rows={3}
        className="w-full rounded border border-zinc-300 px-3 py-2 text-sm"
      />
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      <div className="mt-3 flex gap-3">
        <button onClick={() => submit('APPROVED')} disabled={busy}
          className="rounded bg-green-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">
          Approve
        </button>
        <button onClick={() => submit('CHANGES_REQUESTED')} disabled={busy}
          className="rounded bg-white px-4 py-2 text-sm font-medium text-red-600 ring-1 ring-red-300 disabled:opacity-50">
          Request correction
        </button>
      </div>
    </div>
  );
}
