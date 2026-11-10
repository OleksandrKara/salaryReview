'use client';

import { useEffect, useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language, LeadOwnerCard } from '../../lib/types';

export default function LeadOwnerPanel({ leadId, language, version }: { leadId: number; language: Language | null; version: number }) {
  const [card, setCard] = useState<LeadOwnerCard | null>(null);
  const [changing, setChanging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);

  async function load() {
    const res = await fetch(`/api/leads/${leadId}/owner`, { cache: 'no-store' });
    if (res.ok) setCard(await res.json());
  }

  useEffect(() => { void load(); }, [leadId, version]); // eslint-disable-line react-hooks/exhaustive-deps

  async function assign(userId: number | null) {
    setBusy(true);
    setError(false);
    const res = await fetch(`/api/leads/${leadId}/owner`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId }) });
    setBusy(false);
    if (!res.ok) { setError(true); return; }
    setChanging(false);
    await load();
  }

  if (!card) return null;
  const o = card.owner;
  const mine = o.userId != null && o.userId === card.meId;
  const name = o.kind === 'TEAM' ? t(language, 'ownerTeamLong') : `${o.name ?? ''}${mine ? ` (${t(language, 'ownerYou')})` : ''}`;
  const tone = o.kind === 'TEAM' ? 'bg-zinc-100 text-zinc-800' : mine ? 'bg-emerald-100 text-emerald-900' : 'bg-indigo-100 text-indigo-900';

  return (
    <div className="mt-2 flex min-w-0 flex-col gap-1.5" data-testid="lead-owner">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-xs uppercase tracking-wide text-zinc-500">{t(language, 'ownerLabel')}</span>
        <span className={`rounded-full px-2.5 py-1 text-sm font-medium ${tone}`}>{name}</span>
        {o.kind === 'DEFAULT' && <span className="text-xs text-zinc-500">{t(language, 'ownerKindDEFAULT')}</span>}
        {!mine && (
          <button type="button" disabled={busy} onClick={() => assign(card.meId)} className="rounded px-2 py-1 text-xs text-zinc-800 ring-1 ring-zinc-300 hover:bg-zinc-50">
            {t(language, 'ownerAssignMe')}
          </button>
        )}
        <button type="button" disabled={busy} onClick={() => setChanging(!changing)} aria-expanded={changing} className="rounded px-2 py-1 text-xs text-zinc-600 hover:bg-zinc-100">
          {t(language, 'ownerChange')}
        </button>
      </div>
      {changing && (
        <div className="flex flex-wrap items-center gap-2">
          <select
            aria-label={t(language, 'ownerChange')} disabled={busy} value={o.kind === 'ASSIGNED' ? String(o.userId) : ''}
            onChange={(e) => assign(e.target.value ? Number(e.target.value) : null)}
            className="rounded border border-zinc-300 px-2 py-1.5 text-sm"
          >
            <option value="">{t(language, 'ownerBackToDefault')}</option>
            {card.candidates.map((c) => <option key={c.userId} value={c.userId}>{c.name}</option>)}
          </select>
        </div>
      )}
      {o.kind === 'TEAM' && <p className="text-xs text-zinc-500">{t(language, 'ownerTeamHint')}</p>}
      {error && <p className="text-xs text-red-700">{t(language, 'leadErr')}</p>}
    </div>
  );
}
