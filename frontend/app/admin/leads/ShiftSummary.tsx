'use client';

import { useEffect, useState } from 'react';
import type { Language, ShiftSummaryData } from '../../lib/types';
import { t, tf } from '../../lib/i18n';
import { formatWait, speedTone } from '../../lib/responseTime';

const POLL_MS = 30_000;
const TZ = 'America/Los_Angeles';

function clock(iso: string, lang: Language | null) {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    timeZone: TZ, hour: 'numeric', minute: '2-digit', ...(Date.now() - new Date(iso).getTime() > 12 * 3600_000 ? { month: 'short', day: 'numeric' } : {}),
  });
}

const TONE: Record<string, string> = {
  good: 'text-emerald-700',
  ok: 'text-amber-700',
  slow: 'text-red-700',
  none: 'text-zinc-500',
};

export default function ShiftSummary({ language }: { language: Language | null }) {
  const [data, setData] = useState<ShiftSummaryData | null>(null);
  const [failed, setFailed] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const res = await fetch('/api/metrics/shift', { cache: 'no-store' });
        if (!res.ok) throw new Error(String(res.status));
        const body = (await res.json()) as ShiftSummaryData;
        if (alive) { setData(body); setFailed(false); setNow(Date.now()); }
      } catch {
        if (alive) setFailed(true);
      }
    };
    void load();
    const timer = setInterval(() => { if (document.visibilityState === 'visible') void load(); }, POLL_MS);
    const onVisible = () => { if (document.visibilityState === 'visible') void load(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => { alive = false; clearInterval(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, []);

  if (!data) {
    return (
      <section className="mb-5 rounded-xl border border-zinc-200 bg-white p-4 text-sm text-zinc-500" aria-busy={!failed}>
        {failed ? t(language, 'rtLoadError') : '...'}
      </section>
    );
  }

  const ago = (iso: string | null) => (iso ? formatWait(Math.max(0, (now - new Date(iso).getTime()) / 1000), language) : '-');
  const heading = !data.shiftStart
    ? t(language, 'shiftNotOn')
    : data.onShift
      ? tf(language, 'shiftOnSince', { time: clock(data.shiftStart, language) })
      : tf(language, 'shiftLast', { from: clock(data.shiftStart, language), to: data.shiftEnd ? clock(data.shiftEnd, language) : '-' });

  return (
    <section className="mb-5 rounded-xl border border-zinc-200 bg-white p-4" aria-label={t(language, 'shiftTitle')}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h2 className="text-sm font-semibold">{t(language, 'shiftTitle')}</h2>
        <span className={`text-xs ${data.onShift ? 'text-emerald-700' : 'text-zinc-500'}`}>{heading}</span>
      </div>

      {!data.shiftStart ? (
        <p className="mt-2 text-sm text-zinc-600">{t(language, 'shiftNone')}</p>
      ) : (
        <dl className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div>
            <dt className="text-xs text-zinc-500">{t(language, 'shiftLeadsProcessed')}</dt>
            <dd className="text-2xl font-semibold tabular-nums" data-testid="shift-leads">{data.leadsProcessed}</dd>
          </div>
          <div>
            <dt className="text-xs text-zinc-500">{t(language, 'shiftReplies')}</dt>
            <dd className="text-2xl font-semibold tabular-nums">{data.replies}</dd>
          </div>
          <div>
            <dt className="text-xs text-zinc-500">{t(language, 'shiftMedian')}</dt>
            <dd className={`text-2xl font-semibold tabular-nums ${TONE[speedTone(data.medianSeconds)]}`}>{formatWait(data.medianSeconds, language)}</dd>
          </div>
          <div>
            <dt className="text-xs text-zinc-500">{t(language, 'shiftNewWaiting')}</dt>
            <dd className="text-2xl font-semibold tabular-nums">{data.newLeadsWaiting}</dd>
            {data.oldestNewLeadAt ? <dd className="text-xs text-zinc-500">{tf(language, 'shiftOldest', { ago: ago(data.oldestNewLeadAt) })}</dd> : null}
          </div>
        </dl>
      )}

      <p className={`mt-3 text-sm ${data.unansweredCount > 0 ? 'font-medium text-amber-800' : 'text-zinc-500'}`} data-testid="shift-unanswered">
        {data.unansweredCount > 0
          ? `${t(language, 'shiftUnanswered')}: ${data.unansweredCount} (${tf(language, 'shiftUnansweredOldest', { wait: formatWait(data.oldestUnansweredSeconds, language) })})`
          : t(language, 'shiftAllClear')}
      </p>
      {data.shiftStart ? <p className="mt-1 text-xs text-zinc-400">{t(language, 'shiftHow')}</p> : null}
    </section>
  );
}
