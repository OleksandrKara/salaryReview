'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language, OutdatedPage } from '../../lib/types';

type Seg = 'all' | 'clients' | 'never';

export function LockIcon({ className = 'h-3.5 w-3.5' }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <rect x="3" y="7" width="10" height="7" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  );
}

function day(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleDateString(lang === 'RU' ? 'ru-RU' : 'en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

export default function OutdatedPanel({ language, onTotal }: { language: Language | null; onTotal: (n: number) => void }) {
  const [q, setQ] = useState('');
  const [seg, setSeg] = useState<Seg>('all');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<OutdatedPage | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);

  useEffect(() => {
    const mine = ++seq.current;
    const timer = setTimeout(() => {
      setLoading(true);
      fetch(`/api/leads/outdated?q=${encodeURIComponent(q.trim())}&seg=${seg}&page=${page}`, { cache: 'no-store' })
        .then((r) => (r.ok ? (r.json() as Promise<OutdatedPage>) : Promise.reject(new Error(String(r.status)))))
        .then((d) => {
          if (mine !== seq.current) return;
          setData(d);
          setFailed(false);
          if (!q.trim() && seg === 'all') onTotal(d.total);
        })
        .catch(() => { if (mine === seq.current) setFailed(true); })
        .finally(() => { if (mine === seq.current) setLoading(false); });
    }, q ? 250 : 0);
    return () => clearTimeout(timer);
  }, [q, seg, page, onTotal]);

  const segs: { key: Seg; label: string }[] = [
    { key: 'all', label: t(language, 'outdatedSegAll') },
    { key: 'clients', label: t(language, 'outdatedSegClients') },
    { key: 'never', label: t(language, 'outdatedSegNever') },
  ];
  const count = (s: Seg) => (data ? (s === 'all' ? data.clients + data.never : s === 'clients' ? data.clients : data.never) : null);

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-dashed border-zinc-300 bg-zinc-50/70 p-3 sm:p-4" aria-label={t(language, 'crmTabOutdated')}>
      <p className="flex items-start gap-2 text-xs leading-relaxed text-zinc-600">
        <LockIcon className="mt-0.5 h-4 w-4 shrink-0 text-zinc-500" />
        <span>{t(language, 'outdatedBanner')}</span>
      </p>

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <input
          type="search"
          value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }}
          placeholder={t(language, 'outdatedSearch')}
          aria-label={t(language, 'outdatedSearch')}
          className="w-full rounded border border-zinc-300 bg-white px-3 py-2 text-sm focus:border-zinc-500 focus:outline-none sm:max-w-xs"
        />
        <div className="flex flex-wrap gap-1.5" role="group">
          {segs.map((s) => (
            <button
              key={s.key}
              type="button"
              aria-pressed={seg === s.key}
              onClick={() => { setSeg(s.key); setPage(0); }}
              className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs ring-1 ${seg === s.key ? 'bg-zinc-700 text-white ring-zinc-700' : 'bg-white text-zinc-600 ring-zinc-300 hover:bg-zinc-100'}`}
            >
              {s.label}
              {count(s.key) != null && <span className="tabular-nums opacity-70">{count(s.key)}</span>}
            </button>
          ))}
        </div>
      </div>

      {failed && <p className="rounded bg-red-50 px-3 py-2 text-sm text-red-800">{t(language, 'outdatedLoadFail')}</p>}
      {data && data.items.length === 0 && !failed && <p className="py-4 text-center text-sm text-zinc-500">{t(language, 'outdatedEmpty')}</p>}

      {data && data.items.length > 0 && (
        <ul className={`flex flex-col divide-y divide-zinc-200 overflow-hidden rounded-lg bg-white ring-1 ring-zinc-200 transition-opacity ${loading ? 'opacity-60' : ''}`}>
          {data.items.map((r) => (
            <li key={r.id}>
              <Link
                href={`/admin/leads/${r.id}`}
                className="grid grid-cols-1 gap-x-4 gap-y-1 p-3 hover:bg-zinc-50 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_auto] sm:items-center"
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="truncate font-medium">{r.name ?? r.phoneNumber ?? r.email}</span>
                    {r.consulted && <span className="rounded bg-orange-100 px-1.5 py-0.5 text-xs text-orange-800">{t(language, 'outdatedConsulted')}</span>}
                  </div>
                  <div className="break-all text-xs text-zinc-500">
                    {[r.name ? r.phoneNumber : null, r.email].filter(Boolean).join(' · ')}
                  </div>
                </div>
                <div className="text-xs text-zinc-600">
                  {r.visits > 0 ? (
                    <>
                      <span className="font-medium tabular-nums">{r.visits} {t(language, 'outdatedVisits')}</span>
                      {r.lastVisitAt && <span> · {t(language, 'outdatedLast')} {day(r.lastVisitAt, language)}</span>}
                      {r.nextVisitAt && <span className="text-emerald-700"> · {t(language, 'outdatedNext')} {day(r.nextVisitAt, language)}</span>}
                    </>
                  ) : (
                    <span className="text-zinc-400">{t(language, 'outdatedNoVisits')}</span>
                  )}
                </div>
                <div className="text-xs text-zinc-400 sm:text-right">
                  <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-zinc-500">{r.source}</span>
                  <span className="ml-2 tabular-nums">{day(r.createdAt, language)}</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {data && data.pages > 1 && (
        <div className="flex items-center justify-between gap-2 text-sm">
          <button type="button" disabled={page === 0 || loading} onClick={() => setPage(page - 1)} className="rounded px-3 py-1.5 ring-1 ring-zinc-300 hover:bg-zinc-100 disabled:opacity-40">{t(language, 'outdatedPrev')}</button>
          <span className="tabular-nums text-xs text-zinc-500">{page + 1} {t(language, 'outdatedPageOf')} {data.pages}</span>
          <button type="button" disabled={page + 1 >= data.pages || loading} onClick={() => setPage(page + 1)} className="rounded px-3 py-1.5 ring-1 ring-zinc-300 hover:bg-zinc-100 disabled:opacity-40">{t(language, 'outdatedNextPage')}</button>
        </div>
      )}
    </section>
  );
}
