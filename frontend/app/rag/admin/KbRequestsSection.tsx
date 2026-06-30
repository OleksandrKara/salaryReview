'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import type { KbRequest, KbRequestStatus } from '../../lib/types';

const fmt = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '';

const TARGET_LABEL: Record<KbRequest['target'], string> = {
  KB: 'Knowledge base',
  SOP: 'SOP',
  UNSURE: 'Unsure',
};

// Knowledge-gap requests filed from the assistant when it couldn't answer. The owner reviews them
// here, addresses each by extending a KB article or SOP (then re-syncing), and marks resolved.
export default function KbRequestsSection() {
  const [requests, setRequests] = useState<KbRequest[] | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback(() => {
    api.listKbRequests().then(setRequests).catch(() => setRequests([]));
  }, []);
  useEffect(() => { load(); }, [load]);

  async function setStatus(id: number, status: KbRequestStatus) {
    setBusy(id);
    try {
      const updated = await api.setKbRequestStatus(id, status);
      setRequests((xs) => (xs ? xs.map((x) => (x.id === id ? updated : x)) : xs));
    } finally {
      setBusy(null);
    }
  }

  async function remove(id: number) {
    if (!confirm('Delete this request?')) return;
    setBusy(id);
    try {
      await api.deleteKbRequest(id);
      setRequests((xs) => (xs ? xs.filter((x) => x.id !== id) : xs));
    } finally {
      setBusy(null);
    }
  }

  const openCount = (requests ?? []).filter((r) => r.status === 'OPEN').length;

  return (
    <section className="mt-10">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="text-sm font-semibold">Knowledge requests</h2>
        {openCount > 0 ? (
          <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700">
            {openCount} open
          </span>
        ) : null}
      </div>
      <p className="mb-3 text-xs text-zinc-500">
        Questions the assistant couldn’t answer, sent in by staff. Address each by adding a KB article
        or SOP and re-syncing, then mark it resolved.
      </p>

      {requests === null ? (
        <div className="flex items-center gap-3 rounded-lg px-4 py-6 text-sm text-zinc-500 ring-1 ring-zinc-200">
          <Spinner className="h-5 w-5 text-zinc-400" /> Loading…
        </div>
      ) : requests.length === 0 ? (
        <p className="rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">No requests yet.</p>
      ) : (
        <ul className="space-y-2">
          {requests.map((r) => (
            <li key={r.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-zinc-800">{r.question}</p>
                  {r.note ? <p className="mt-0.5 text-xs text-zinc-500">{r.note}</p> : null}
                  <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                    <StatusBadge status={r.status} />
                    <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-[10px] text-zinc-500">
                      {TARGET_LABEL[r.target]}
                    </span>
                    <span className="text-[10px] text-zinc-400">
                      {r.requestedBy} · {fmt(r.createdAt)}
                      {r.status === 'RESOLVED' && r.resolvedBy ? ` · resolved by ${r.resolvedBy}` : ''}
                    </span>
                  </div>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1.5">
                  {r.status === 'OPEN' ? (
                    <div className="flex gap-1.5">
                      <button onClick={() => setStatus(r.id, 'RESOLVED')} disabled={busy === r.id}
                        className="rounded px-2 py-1 text-xs text-green-700 ring-1 ring-green-200 disabled:opacity-50">
                        Resolve
                      </button>
                      <button onClick={() => setStatus(r.id, 'DISMISSED')} disabled={busy === r.id}
                        className="rounded px-2 py-1 text-xs text-zinc-500 ring-1 ring-zinc-200 disabled:opacity-50">
                        Dismiss
                      </button>
                    </div>
                  ) : (
                    <button onClick={() => setStatus(r.id, 'OPEN')} disabled={busy === r.id}
                      className="rounded px-2 py-1 text-xs text-zinc-600 ring-1 ring-zinc-200 disabled:opacity-50">
                      Reopen
                    </button>
                  )}
                  <button onClick={() => remove(r.id)} disabled={busy === r.id}
                    className="rounded px-2 py-1 text-xs text-red-600 ring-1 ring-red-200 disabled:opacity-50">
                    Delete
                  </button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function StatusBadge({ status }: { status: KbRequestStatus }) {
  const map: Record<KbRequestStatus, [string, string]> = {
    OPEN: ['Open', 'bg-amber-50 text-amber-700'],
    RESOLVED: ['Resolved', 'bg-green-50 text-green-700'],
    DISMISSED: ['Dismissed', 'bg-zinc-100 text-zinc-500'],
  };
  const [label, cls] = map[status];
  return <span className={`rounded px-1.5 py-0.5 text-[10px] ${cls}`}>{label}</span>;
}
