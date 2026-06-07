'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../lib/api';
import { Spinner } from './Spinner';

// "Sync now": force a fresh pull from Square (busts the read cache), then refresh the page. The spinner
// stays on through the pull + re-fetch and clears when the page comes back with a newer synced time
// (the `syncedAt` prop changes). A safety timeout guarantees it never spins forever.
export function SyncButton({ syncedAt }: { syncedAt: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);

  // The page re-rendered with a new synced time → the refresh landed → stop spinning.
  useEffect(() => {
    setBusy(false);
  }, [syncedAt]);

  // Safety net: never leave the spinner stuck even if the refresh doesn't change the timestamp.
  useEffect(() => {
    if (!busy) return;
    const t = setTimeout(() => setBusy(false), 15000);
    return () => clearTimeout(t);
  }, [busy]);

  async function sync() {
    setBusy(true);
    try {
      await api.syncSquare();
      router.refresh();
    } catch {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      onClick={sync}
      disabled={busy}
      title="Pull fresh data from Square now"
      data-testid="sync-btn"
      className="inline-flex items-center gap-1 text-xs text-zinc-400 hover:text-zinc-700 disabled:opacity-50"
    >
      {busy ? <Spinner className="h-3 w-3" /> : (
        <svg viewBox="0 0 24 24" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v6h-6" />
        </svg>
      )}
      {busy ? 'Syncing…' : 'Sync'}
    </button>
  );
}
