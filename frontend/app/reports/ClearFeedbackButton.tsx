'use client';

import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../lib/api';

// Owner/manager: clear a provider's response (approval / change-request) for one period, e.g. they
// approved by mistake or a correction was handled. Rendered next to the badge on the report.
export default function ClearFeedbackButton({
  providerId,
  year,
  month,
  half,
}: {
  providerId: number;
  year: number;
  month: number;
  half: 'FIRST' | 'SECOND';
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [busy, setBusy] = useState(false);

  async function clear() {
    if (!window.confirm("Clear this provider's response for this period?")) return;
    setBusy(true);
    try {
      await api.clearFeedback(providerId, year, month, half);
      startTransition(() => router.refresh());
    } finally {
      setBusy(false);
    }
  }

  return (
    <button onClick={clear} disabled={busy || pending} title="Clear (undo) this response"
      className="leading-none text-zinc-400 hover:text-zinc-700 disabled:opacity-50">×</button>
  );
}
