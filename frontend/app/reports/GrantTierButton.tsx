'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '../lib/api';

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
  const [busy, setBusy] = useState(false);

  async function toggle() {
    setBusy(true);
    try {
      if (granted) await api.revokeTier(providerId, year, month);
      else await api.grantTier(providerId, year, month);
      router.refresh();
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      onClick={toggle}
      disabled={busy}
      className={`rounded px-2 py-0.5 text-xs font-medium ring-1 disabled:opacity-50 ${
        granted
          ? 'bg-amber-50 text-amber-700 ring-amber-300 hover:bg-amber-100'
          : 'bg-zinc-50 text-zinc-600 ring-zinc-300 hover:bg-zinc-100'
      }`}
      title={granted ? 'Remove the manual 50/50 grant' : 'Manually grant 50/50 for this month'}
    >
      {busy ? '…' : granted ? 'Revoke 50/50' : 'Grant 50/50'}
    </button>
  );
}
