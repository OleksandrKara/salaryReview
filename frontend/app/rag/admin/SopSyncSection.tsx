'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import { SyncBadge } from '../../kb/KbManager';
import type { SopSyncItem } from '../../lib/types';

// SOP → RAG sync. Mirrors the KB section: a bulk "Sync" drives per-SOP syncs sequentially (live
// progress) and a per-row sync pushes one. The current published version is what gets indexed; a
// draft-only SOP can't be synced, and PII is rejected whole and surfaced inline.
export default function SopSyncSection() {
  const [items, setItems] = useState<SopSyncItem[] | null>(null);
  const [current, setCurrent] = useState<number | null>(null); // id being processed
  const [running, setRunning] = useState(false);

  const load = useCallback(() => {
    api.listSopRagSync().then(setItems).catch(() => setItems([]));
  }, []);
  useEffect(() => { load(); }, [load]);

  function replace(updated: SopSyncItem) {
    setItems((xs) => (xs ? xs.map((x) => (x.id === updated.id ? updated : x)) : xs));
  }

  async function syncOne(id: number) {
    setCurrent(id);
    try {
      replace(await api.syncSopRag(id));
    } finally {
      setCurrent(null);
    }
  }

  async function syncAll() {
    if (!items || running) return;
    setRunning(true);
    try {
      for (const s of items.filter((x) => x.published && x.syncStatus !== 'SYNCED')) {
        setCurrent(s.id);
        try {
          replace(await api.syncSopRag(s.id));
        } catch {
          /* network error — leave the row as-is and continue */
        }
      }
    } finally {
      setCurrent(null);
      setRunning(false);
    }
  }

  const pending = (items ?? []).filter((s) => s.published && s.syncStatus !== 'SYNCED').length;

  return (
    <section className="mt-10">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold">SOPs</h2>
        <button
          onClick={syncAll}
          disabled={running || pending === 0}
          className="inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
        >
          {running ? <Spinner className="h-4 w-4 text-white" /> : null}
          {running ? 'Syncing…' : `Sync${pending ? ` (${pending})` : ''}`}
        </button>
      </div>
      <p className="mb-3 text-xs text-zinc-500">
        Push published SOPs into the assistant — the current published version is indexed. Flagged
        content (PII) is rejected as a whole and shown as an error below.
      </p>

      {items === null ? (
        <div className="flex items-center gap-3 rounded-lg px-4 py-6 text-sm text-zinc-500 ring-1 ring-zinc-200">
          <Spinner className="h-5 w-5 text-zinc-400" /> Loading…
        </div>
      ) : items.length === 0 ? (
        <p className="rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">
          No active SOPs yet — create some on the SOPs admin page.
        </p>
      ) : (
        <ul className="space-y-2">
          {items.map((s) => (
            <li key={s.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <span className="text-sm font-medium text-zinc-800">{s.title}</span>
                  <span className="ml-2 text-xs text-zinc-400">{s.category}</span>
                  <div className="mt-1 flex items-center gap-1.5">
                    {s.published ? (
                      <SyncBadge status={s.syncStatus} />
                    ) : (
                      <span className="rounded bg-amber-50 px-2 py-0.5 text-[10px] text-amber-700">
                        No published version
                      </span>
                    )}
                    {current === s.id ? <span className="text-[10px] text-zinc-400">processing…</span> : null}
                  </div>
                </div>
                <button
                  onClick={() => syncOne(s.id)}
                  disabled={running || current === s.id || !s.published}
                  className="shrink-0 rounded px-2 py-1 text-xs text-zinc-500 ring-1 ring-zinc-200 disabled:opacity-50"
                >
                  Sync
                </button>
              </div>
              {s.syncStatus === 'ERROR' && s.lastSyncError ? (
                <p className="mt-2 border-t border-zinc-100 pt-2 text-xs text-red-600">{s.lastSyncError}</p>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
