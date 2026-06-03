'use client';

import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../lib/api';
import { Spinner } from './Spinner';

// "Sync now": force a fresh pull from Square (busts the read cache), then refresh the page so the
// numbers and the synced timestamp update. Sits next to the SyncBadge.
export function SyncButton() {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [busy, setBusy] = useState(false);
  const working = busy || pending;

  async function sync() {
    setBusy(true);
    try {
      await api.syncSquare();
      start(() => router.refresh());
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      onClick={sync}
      disabled={working}
      title="Pull fresh data from Square now"
      className="inline-flex items-center gap-1 text-xs text-zinc-400 hover:text-zinc-700 disabled:opacity-50"
    >
      {working ? <Spinner className="h-3 w-3" /> : (
        <svg viewBox="0 0 24 24" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v6h-6" />
        </svg>
      )}
      {working ? 'Syncing…' : 'Sync'}
    </button>
  );
}
