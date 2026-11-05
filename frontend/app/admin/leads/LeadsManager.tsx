'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language, Lead } from '../../lib/types';

const STATUSES = ['NEW', 'CONTACTED', 'BOOKED', 'LOST'] as const;
const STATUS_STYLE: Record<string, string> = {
  NEW: 'bg-blue-100 text-blue-800',
  CONTACTED: 'bg-zinc-100 text-zinc-700',
  BOOKED: 'bg-green-100 text-green-800',
  LOST: 'bg-zinc-100 text-zinc-500',
};

function fmt(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export default function LeadsManager({ initialLeads, language = null }: { initialLeads: Lead[]; language?: Language | null }) {
  const [filter, setFilter] = useState<string>('ALL');
  const [q, setQ] = useState('');

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return initialLeads.filter((l) =>
      (filter === 'ALL' || l.status === filter) &&
      (!needle || [l.name, l.phoneNumber, l.email, l.source].some((v) => v?.toLowerCase().includes(needle))));
  }, [initialLeads, filter, q]);

  return (
    <div className="flex flex-col gap-4">
      <input
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder={t(language, 'leadsSearch')}
        className="w-full rounded border border-zinc-300 px-3 py-2 text-sm focus:border-zinc-500 focus:outline-none"
      />
      <div className="flex flex-wrap gap-2">
        {['ALL', ...STATUSES].map((s) => (
          <button
            key={s}
            onClick={() => setFilter(s)}
            className={`rounded-full px-3 py-1 text-sm ring-1 ${filter === s ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300'}`}
          >
            {s === 'ALL' ? t(language, 'leadsFilterAll') : t(language, `leadStatus${s}` as 'leadStatusNEW')}
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="text-sm text-zinc-500">{t(language, 'leadsEmpty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-zinc-200 rounded-lg ring-1 ring-zinc-200">
          {shown.map((l) => {
            const overdue = l.callbackAt != null && new Date(l.callbackAt) <= new Date() && (l.status === 'NEW' || l.status === 'CONTACTED');
            return (
              <li key={l.id}>
                <Link href={`/admin/leads/${l.id}`} className="flex flex-col gap-1 p-3 hover:bg-zinc-50 sm:flex-row sm:items-center sm:gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-medium">{l.name ?? l.phoneNumber ?? l.email}</div>
                    <div className="truncate text-xs text-zinc-500">
                      {l.name && l.phoneNumber ? `${l.phoneNumber} · ` : ''}{l.source} · {l.channel}
                      {l.touches > 1 ? ` · ${l.touches} ${t(language, 'leadTouches')}` : ''}
                    </div>
                  </div>
                  {overdue && <span className="w-fit rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800">{t(language, 'leadReminderDue')}</span>}
                  <span className={`w-fit rounded px-2 py-0.5 text-xs ${STATUS_STYLE[l.status]}`}>{t(language, `leadStatus${l.status}` as 'leadStatusNEW')}</span>
                  <span className="text-xs text-zinc-500 sm:w-28 sm:text-right">{fmt(l.lastTouchAt, language)}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
