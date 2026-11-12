'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import type { Language, ShiftComparison, ShiftSummaryData } from '../../lib/types';
import { t, tf } from '../../lib/i18n';
import WaitingRows from '../../components/WaitingRows';
import { formatWait, shiftState, SLOW_WAIT_SECONDS, speedTone } from '../../lib/responseTime';

const POLL_MS = 30_000;
const TZ = 'America/Los_Angeles';
const COMPARE_TABS: { id: ShiftComparison['period']; key: 'rt7d' | 'rt30d' | 'rtAll' }[] = [
  { id: '7d', key: 'rt7d' },
  { id: '30d', key: 'rt30d' },
  { id: 'all', key: 'rtAll' },
];

function clock(iso: string, lang: Language | null) {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    timeZone: TZ, hour: 'numeric', minute: '2-digit', ...(Date.now() - new Date(iso).getTime() > 12 * 3600_000 ? { month: 'short', day: 'numeric' } : {}),
  });
}

const TONE: Record<string, string> = { good: 'text-emerald-700', ok: 'text-amber-700', slow: 'text-red-700', none: 'text-zinc-400' };

export default function ShiftSummary({ language }: { language: Language | null }) {
  const [data, setData] = useState<ShiftSummaryData | null>(null);
  const [failed, setFailed] = useState(false);
  const [compare, setCompare] = useState<ShiftComparison['period']>('7d');

  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const res = await fetch('/api/metrics/shift', { cache: 'no-store' });
        if (!res.ok) throw new Error(String(res.status));
        const body = (await res.json()) as ShiftSummaryData;
        if (alive) { setData(body); setFailed(false); }
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
      <section className="mb-4 rounded-xl border border-zinc-200 bg-white p-3 text-sm text-zinc-500" aria-busy={!failed} data-testid="shift-loading">
        {failed ? t(language, 'rtLoadError') : <div className="h-14 animate-pulse rounded-lg bg-zinc-100" />}
      </section>
    );
  }

  const state = shiftState(data);
  const heading = state === 'none'
    ? t(language, 'shiftNotOn')
    : data.onShift
      ? tf(language, 'shiftOnSince', { time: clock(data.shiftStart!, language) })
      : tf(language, 'shiftLast', { from: clock(data.shiftStart!, language), to: data.shiftEnd ? clock(data.shiftEnd, language) : '-' });
  const cmp = data.comparisons.find((c) => c.period === compare) ?? null;
  const week = data.comparisons.find((c) => c.period === '7d') ?? null;
  const tone = speedTone(data.medianSeconds);

  return (
    <section className="mb-4 rounded-xl border border-zinc-200 bg-white px-3 py-3 sm:px-4" aria-label={t(language, 'shiftTitle')}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5">
        <h2 className="!font-sans text-sm font-semibold">{t(language, 'shiftTitle')}</h2>
        <span className={`text-xs ${data.onShift ? 'text-emerald-700' : 'text-zinc-500'}`}>{heading}</span>
      </div>

      {state === 'none' ? (
        <p className="mt-2 text-sm text-zinc-600">{t(language, 'shiftNone')}</p>
      ) : (
        <div className="mt-2 grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1">
          <div className="min-w-0">
            {data.replies > 0 ? (
              <>
                <p className={`whitespace-nowrap text-2xl font-semibold leading-none tabular-nums sm:text-3xl ${TONE[tone]}`}>{formatWait(data.medianSeconds, language)}</p>
                <p className="mt-1 text-xs text-zinc-500">
                  {week?.teamMedianSeconds != null
                    ? tf(language, 'shiftMedianVsTeam', { team: formatWait(week.teamMedianSeconds, language) })
                    : t(language, 'shiftMedianShort')}
                </p>
              </>
            ) : (
              <p className="text-sm text-zinc-500">{t(language, 'shiftNoReplies')}</p>
            )}
          </div>
          <div className="text-right">
            <p className="text-2xl font-semibold leading-none tabular-nums sm:text-3xl" data-testid="shift-leads">{data.leadsProcessed}</p>
            <p className="mt-1 text-xs text-zinc-500">{t(language, 'shiftLeadsProcessed')}</p>
          </div>
        </div>
      )}

      {data.waiting.length > 0 ? (
        <div
          className={`mt-2 rounded-lg border ${data.waiting.some((w) => w.seconds > SLOW_WAIT_SECONDS) ? 'border-red-200 bg-red-50 text-red-900' : 'border-amber-200 bg-amber-50 text-amber-900'}`}
          data-testid="shift-unanswered"
          data-slow={data.waiting.some((w) => w.seconds > SLOW_WAIT_SECONDS) ? 'true' : 'false'}
        >
          <p className="px-3 pt-2 text-xs font-semibold">{tf(language, 'shiftWaitingChip', { n: data.unansweredCount })}</p>
          <div className="px-3 pb-1">
            <WaitingRows
              testId="shift-waiting"
              language={language}
              activeBusinessId={null}
              rows={data.waiting.map((w) => ({ key: w.phone, name: w.name, last4: w.phoneLast4, phone: w.phone, businessId: null, preview: w.preview, since: w.since, seconds: w.seconds, leadId: w.leadId, leadStatus: w.leadStatus, lastAutoKey: w.lastAutoKey, lastAutoAt: w.lastAutoAt }))}
            />
          </div>
          {data.unansweredCount > data.waiting.length ? <p className="px-3 pb-2 text-xs opacity-80">{tf(language, 'rtMoreWaiting', { n: data.unansweredCount - data.waiting.length })}</p> : null}
        </div>
      ) : null}
      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
        {data.waiting.length === 0 && state !== 'none' ? (
          <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-emerald-800">{t(language, 'shiftAllClear')}</span>
        ) : null}
        {data.newLeadsWaiting > 0 ? (
          <Link href="/admin/leads" className="rounded-full bg-zinc-100 px-2.5 py-1 text-zinc-700">{tf(language, 'shiftNewChip', { n: data.newLeadsWaiting })}</Link>
        ) : null}
      </div>

      {state === 'none' ? (
        <p className="mt-2 text-right"><Link href="/admin/response-times" className="text-xs text-zinc-600 underline">{t(language, 'shiftAllManagers')}</Link></p>
      ) : (
        <div className="mt-2 border-t border-zinc-100 pt-2">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
            <div role="tablist" aria-label={t(language, 'shiftCompare')} className="inline-flex rounded-lg bg-zinc-100 p-0.5">
              {COMPARE_TABS.map((p) => (
                <button
                  key={p.id}
                  role="tab"
                  aria-selected={compare === p.id}
                  onClick={() => setCompare(p.id)}
                  className={`min-h-8 rounded-md px-2.5 text-xs font-medium ${compare === p.id ? 'bg-white text-zinc-900 shadow-sm' : 'text-zinc-600'}`}
                >
                  {t(language, p.key)}
                </button>
              ))}
            </div>
            <p className="min-w-0 text-xs text-zinc-600" data-testid="shift-compare">
              {cmp && cmp.myMedianSeconds != null ? (
                <>
                  {tf(language, 'shiftCompareLine', { you: formatWait(cmp.myMedianSeconds, language), team: formatWait(cmp.teamMedianSeconds, language) })}
                  {cmp.rank != null && cmp.ranked > 1 ? ` · ${tf(language, 'shiftCompareRank', { rank: cmp.rank, n: cmp.ranked })}` : ''}
                </>
              ) : (
                t(language, 'shiftCompareNoData')
              )}
            </p>
          </div>
          <div className="mt-1 flex items-start justify-between gap-3">
            <details className="min-w-0 text-[11px] text-zinc-400">
              <summary className="min-h-6 cursor-pointer select-none py-0.5">{t(language, 'shiftHowTitle')}</summary>
              <p className="mt-0.5 text-zinc-500">{t(language, 'shiftHow')}</p>
            </details>
            <Link href="/admin/response-times" className="shrink-0 py-0.5 text-xs text-zinc-600 underline">{t(language, 'shiftAllManagers')}</Link>
          </div>
        </div>
      )}
    </section>
  );
}
