'use client';

import { useEffect, useState } from 'react';
import type { Language, ManagerResponseRow, ResponseLeaderboard } from '../../lib/types';
import { t, tf } from '../../lib/i18n';
import { formatWait, rankManagers, speedTone } from '../../lib/responseTime';

type Period = 'today' | '7d' | '30d';
const PERIODS: { id: Period; key: 'rtToday' | 'rt7d' | 'rt30d' }[] = [
  { id: 'today', key: 'rtToday' },
  { id: '7d', key: 'rt7d' },
  { id: '30d', key: 'rt30d' },
];
const TONE_TEXT: Record<string, string> = { good: 'text-emerald-700', ok: 'text-amber-700', slow: 'text-red-700', none: 'text-zinc-500' };
const TONE_BADGE: Record<string, string> = {
  good: 'bg-emerald-50 text-emerald-800',
  ok: 'bg-amber-50 text-amber-800',
  slow: 'bg-red-50 text-red-800',
  none: 'bg-zinc-100 text-zinc-600',
};
const TONE_STRIPE: Record<string, string> = { good: 'bg-emerald-500', ok: 'bg-amber-500', slow: 'bg-red-500', none: 'bg-zinc-300' };

type Lang = Language | null;
const pct = (n: number | null) => (n == null ? '-' : `${n}%`);

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="min-w-0 rounded-lg bg-zinc-50 px-2 py-2">
      <dd className="whitespace-nowrap text-[13px] font-semibold tabular-nums text-zinc-900">{value}</dd>
      <dt className="text-[11px] leading-tight text-zinc-500">{label}</dt>
    </div>
  );
}

function StatsGrid({ m, language, withWork }: { m: Pick<ManagerResponseRow, 'averageSeconds' | 'within5Percent' | 'within15Percent' | 'replies'> & Partial<Pick<ManagerResponseRow, 'leadsProcessed' | 'shiftHours'>>; language: Lang; withWork: boolean }) {
  return (
    <dl className={`grid gap-1.5 ${withWork ? "grid-cols-3" : "grid-cols-2 min-[480px]:grid-cols-4"}`}>
      <Stat label={t(language, 'rtAverage')} value={formatWait(m.averageSeconds, language)} />
      <Stat label={t(language, 'rtWithin5')} value={pct(m.within5Percent)} />
      <Stat label={t(language, 'rtWithin15')} value={pct(m.within15Percent)} />
      <Stat label={t(language, 'rtReplies')} value={m.replies} />
      {withWork ? <Stat label={t(language, 'rtLeads')} value={m.leadsProcessed ?? 0} /> : null}
      {withWork ? <Stat label={t(language, 'rtHours')} value={m.shiftHours ?? 0} /> : null}
    </dl>
  );
}

function ManagerCard({ m, place, open, onToggle, language }: { m: ManagerResponseRow; place: number | null; open: boolean; onToggle: () => void; language: Lang }) {
  const tone = speedTone(m.medianSeconds);
  return (
    <li className="relative overflow-hidden rounded-xl border border-zinc-200 bg-white">
      <span className={`absolute inset-y-0 left-0 w-1 ${TONE_STRIPE[tone]}`} aria-hidden="true" />
      <button
        type="button"
        aria-expanded={open}
        onClick={onToggle}
        className="flex min-h-14 w-full items-center gap-3 py-3 pl-4 pr-3 text-left"
      >
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-xs font-semibold tabular-nums text-zinc-700">
          {place ?? '-'}
        </span>
        <span className="min-w-0 flex-1 break-words text-base [overflow-wrap:anywhere] font-medium text-zinc-900">{m.username}</span>
        <span className="flex shrink-0 flex-col items-end">
          <span className={`rounded-md px-2 py-0.5 text-lg font-semibold tabular-nums ${TONE_BADGE[tone]}`}>{formatWait(m.medianSeconds, language)}</span>
          <span className="mt-0.5 text-[11px] text-zinc-500">{t(language, 'rtMedian')}</span>
        </span>
        <svg viewBox="0 0 20 20" className={`h-4 w-4 shrink-0 text-zinc-400 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true">
          <path d="M5 8l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      <div className="pb-3 pl-4 pr-3">
        <StatsGrid m={m} language={language} withWork />
      </div>
      {open ? (
        <div className="border-t border-zinc-100 bg-zinc-50 py-3 pl-4 pr-3 text-sm text-zinc-700" data-testid={`rt-detail-${m.userId}`}>
          <p className="mb-1.5 text-xs font-medium text-zinc-600">{t(language, 'rtSlowest')}</p>
          {m.slowest.length === 0 ? (
            <p className="text-xs text-zinc-500">{t(language, 'rtSlowestNone')}</p>
          ) : (
            <ul className="space-y-1.5">
              {m.slowest.map((s, k) => (
                <li key={k} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                  <span className="font-semibold tabular-nums">{formatWait(s.seconds, language)}</span>
                  <span className="text-xs text-zinc-500">
                    {`${t(language, 'rtThread')} ${s.phoneLast4} · ${new Date(s.replyAt).toLocaleString(language === 'RU' ? 'ru-RU' : 'en-US', { dateStyle: 'short', timeStyle: 'short' })}`}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </li>
  );
}

export default function ResponseTimesView({ language }: { language: Language | null }) {
  const [period, setPeriod] = useState<Period>('7d');
  const [data, setData] = useState<ResponseLeaderboard | null>(null);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    fetch(`/api/metrics/response?period=${period}`, { cache: 'no-store' })
      .then((res) => (res.ok ? (res.json() as Promise<ResponseLeaderboard>) : Promise.reject(new Error(String(res.status)))))
      .then((body) => { if (alive) { setData(body); setError(false); } })
      .catch(() => { if (alive) setError(true); });
    return () => { alive = false; };
  }, [period]);

  const rows = data ? rankManagers(data.managers) : [];
  const inferred = data ? data.managers.reduce((n, m) => n + m.inferredReplies, 0) : 0;
  const places = new Map<number, number>();
  rows.forEach((m) => { if (m.replies > 0) places.set(m.userId, places.size + 1); });

  return (
    <div className="min-w-0">
      <div role="tablist" className="mb-4 grid w-full grid-cols-3 gap-1 rounded-xl border border-zinc-200 bg-white p-1 sm:inline-grid sm:w-auto">
        {PERIODS.map((p) => (
          <button
            key={p.id}
            role="tab"
            aria-selected={period === p.id}
            onClick={() => setPeriod(p.id)}
            className={`min-h-11 rounded-lg px-4 text-sm font-medium ${period === p.id ? 'bg-zinc-900 text-white' : 'text-zinc-600 hover:bg-zinc-50'}`}
          >
            {t(language, p.key)}
          </button>
        ))}
      </div>

      {error ? <p className="text-sm text-red-700">{t(language, 'rtLoadError')}</p> : null}
      {!data && !error ? <p className="text-sm text-zinc-500">...</p> : null}

      {data ? (
        <>
          {data.waiting.length > 0 ? (
            <section className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-3 py-3 text-amber-900" data-testid="rt-waiting">
              <h2 className="mb-2 !font-sans text-sm font-semibold">{t(language, 'rtWaitingTitle')}</h2>
              <ul className="divide-y divide-amber-200/70">
                {data.waiting.map((w, k) => (
                  <li key={k} className="flex min-h-9 items-center justify-between gap-3 py-1.5 text-sm">
                    <span className="text-xs">{`${t(language, 'rtThread')} ${w.phoneLast4}`}</span>
                    <span className="font-semibold tabular-nums">{formatWait(w.seconds, language)}</span>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {rows.length === 0 ? (
            <p className="text-sm text-zinc-500">{t(language, 'rtEmpty')}</p>
          ) : (
            <>
              <section className="mb-4 rounded-xl border border-zinc-200 bg-white p-3">
                <div className="mb-2 flex items-baseline justify-between gap-3">
                  <h2 className="!font-sans text-sm font-semibold text-zinc-900">{t(language, 'rtTeam')}</h2>
                  <p className="text-right">
                    <span className={`text-lg font-semibold tabular-nums ${TONE_TEXT[speedTone(data.team.medianSeconds)]}`}>{formatWait(data.team.medianSeconds, language)}</span>
                    <span className="ml-1.5 text-[11px] text-zinc-500">{t(language, 'rtMedian')}</span>
                  </p>
                </div>
                <StatsGrid m={data.team} language={language} withWork={false} />
              </section>
              <ul className="grid grid-cols-1 gap-3 md:grid-cols-2">
                {rows.map((m) => (
                  <ManagerCard key={m.userId} m={m} place={places.get(m.userId) ?? null} open={open === m.userId} onToggle={() => setOpen(open === m.userId ? null : m.userId)} language={language} />
                ))}
              </ul>
            </>
          )}

          <ul className="mt-4 space-y-1 text-xs text-zinc-500">
            {inferred > 0 ? <li>{tf(language, 'rtInferred', { n: inferred })}</li> : null}
            {data.unknownReplies > 0 ? <li>{tf(language, 'rtUnknown', { n: data.unknownReplies })}</li> : null}
            {data.offShiftReplies > 0 ? <li>{tf(language, 'rtOffShift', { n: data.offShiftReplies })}</li> : null}
          </ul>
        </>
      ) : null}
    </div>
  );
}
