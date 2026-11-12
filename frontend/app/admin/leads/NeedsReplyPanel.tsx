'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import WaitingRows, { type WaitingRow } from '../../components/WaitingRows';
import { needsReplyRows } from '../../lib/responseTime';
import { t } from '../../lib/i18n';
import type { Language, NeedsReplyQueue } from '../../lib/types';

export default function NeedsReplyPanel({ queue, failed, language, onChanged }: { queue: NeedsReplyQueue | null; failed: boolean; language: Language | null; onChanged: () => void }) {
  const router = useRouter();
  const [creating, setCreating] = useState<string | null>(null);
  const [errorKey, setErrorKey] = useState<string | null>(null);

  async function createLead(row: WaitingRow) {
    setCreating(row.key);
    setErrorKey(null);
    try {
      const res = await fetch('/api/leads/from-chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: row.phone }) });
      if (!res.ok) throw new Error(String(res.status));
      const d = await res.json();
      onChanged();
      router.push(`/admin/leads/${d.id}`);
    } catch {
      setErrorKey(row.key);
    } finally {
      setCreating(null);
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-3" data-testid="needs-reply-panel">
      <p className="rounded-lg bg-zinc-50 px-3 py-2 text-xs text-zinc-600 ring-1 ring-zinc-200">{t(language, 'nrHint')}</p>
      {failed && !queue ? <p className="text-sm text-red-700">{t(language, 'nrLoadFailed')}</p> : null}
      {queue && queue.items.length === 0 ? (
        <p className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800 ring-1 ring-green-200" data-testid="needs-reply-empty">{t(language, 'nrEmpty')}</p>
      ) : null}
      {queue && queue.items.length > 0 ? (
        <div className="min-w-0 rounded-lg bg-red-50 px-3 py-1 text-red-900 ring-1 ring-red-200">
          <WaitingRows rows={needsReplyRows(queue)} language={language} activeBusinessId={null} testId="needs-reply" onCreateLead={createLead} creatingKey={creating} errorKey={errorKey} />
        </div>
      ) : null}
    </div>
  );
}
