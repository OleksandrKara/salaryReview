'use client';

import { useRouter } from 'next/navigation';
import { useState, useTransition } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';

// Owner/manager control to manually grant (or revoke) the 50/50 tier for a provider in a month,
// when the automatic service count falls short. Hidden when the tier was earned automatically.
export default function GrantTierButton({
  providerId,
  year,
  month,
  granted,
}: {
  providerId: number;
  year: number;
  month: number;
  granted: boolean;
}) {
  const router = useRouter();
  const [saving, setSaving] = useState(false);
  // The save is quick, but the router.refresh() that follows re-runs the (slow) Square fetch.
  // useTransition keeps the spinner up until that re-render completes.
  const [pending, startTransition] = useTransition();
  const busy = saving || pending;

  async function toggle() {
    setSaving(true);
    try {
      if (granted) await api.revokeTier(providerId, year, month);
      else await api.grantTier(providerId, year, month);
      startTransition(() => router.refresh());
    } finally {
      setSaving(false);
    }
  }

  return (
    <button
      onClick={toggle}
      disabled={busy}
      className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ring-1 disabled:opacity-50 ${
        granted
          ? 'bg-amber-50 text-amber-700 ring-amber-300 hover:bg-amber-100'
          : 'bg-zinc-50 text-zinc-600 ring-zinc-300 hover:bg-zinc-100'
      }`}
      title={granted ? 'Remove the manual 50/50 grant' : 'Manually grant 50/50 for this month'}
    >
      {busy && <Spinner className="h-3 w-3" />}
      {busy ? 'Saving…' : granted ? 'Revoke 50/50' : 'Grant 50/50'}
    </button>
  );
}
