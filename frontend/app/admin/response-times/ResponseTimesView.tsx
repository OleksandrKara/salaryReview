'use client';

import { useEffect, useState } from 'react';
import type { BusinessResponseReport, Language, ManagerResponseRow, ResponseOverview, ResponseStats } from '../../lib/types';
import { t, tf } from '../../lib/i18n';
import WaitingRows, { type WaitingRow } from '../../components/WaitingRows';
import { formatWait, rankManagers, SLOW_WAIT_SECONDS, speedTone } from '../../lib/responseTime';

type Period = 'today' | '7d' | '30d' | 'all';
const PERIODS: { id: Period; key: 'rtToday' | 'rt7d' | 'rt30d' | 'rtAll' }[] = [
  { id: 'today', key: 'rtToday' },
  { id: '7d', key: 'rt7d' },
  { id: '30d', key: 'rt30d' },
  { id: 'all', key: 'rtAll' },
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

function Skeleton() {
  return (
    <div aria-busy="true" data-testid="rt-skeleton" className="space-y-3">
      <div className="h-24 animate-pulse rounded-xl bg-zinc-100" />
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div className="h-44 animate-pulse rounded-xl bg-zinc-100" />
        <div className="h-44 animate-pulse rounded-xl bg-zinc-100" />
      </div>
    </div>
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

function Kpi({ title, stats, waiting, waitingLabel, language, testId }: { title: string; stats: ResponseStats; waiting: number; waitingLabel: string; language: Lang; testId: string }) {
  const tone = speedTone(stats.medianSeconds);
  return (
    <section className="relative min-w-0 overflow-hidden rounded-xl border border-zinc-200 bg-white p-3 pl-4" data-testid={testId}>
      <span className={`absolute inset-y-0 left-0 w-1 ${TONE_STRIPE[tone]}`} aria-hidden="true" />
      <h2 className="mb-1 !font-sans text-xs font-medium text-zinc-600">{title}</h2>
      <p className="flex items-baseline gap-1.5">
        <span className={`text-2xl font-semibold tabular-nums ${TONE_TEXT[tone]}`}>{stats.replies === 0 ? '-' : formatWait(stats.medianSeconds, language)}</span>
        <span className="text-[11px] text-zinc-500">{t(language, 'rtMedian')}</span>
      </p>
      <dl className="mt-2 grid grid-cols-3 gap-1.5">
        <Stat label={t(language, 'rtWithin5')} value={pct(stats.within5Percent)} />
        <Stat label={t(language, 'rtReplies')} value={stats.replies} />
        <Stat label={waitingLabel} value={waiting} />
      </dl>
    </section>
  );
}

function Waiting({ title, rows, total, language, activeBusinessId, testId }: { title: string; rows: WaitingRow[]; total?: number; language: Lang; activeBusinessId: number | null | undefined; testId: string }) {
  if (rows.length === 0) return null;
  const slow = rows.some((w) => w.seconds > SLOW_WAIT_SECONDS);
  const box = slow ? 'border-red-200 bg-red-50 text-red-900' : 'border-amber-200 bg-amber-50 text-amber-900';
  const more = total != null && total > rows.length ? total - rows.length : 0;
  return (
    <section className={`mb-3 rounded-xl border px-3 py-3 ${box}`} data-testid={testId} data-slow={slow ? 'true' : 'false'}>
      <h3 className="mb-1 !font-sans text-sm font-semibold">{title}{total != null ? ` · ${total}` : ''}</h3>
      <WaitingRows rows={rows} language={language} activeBusinessId={activeBusinessId} testId={testId} />
      {more > 0 ? <p className="mt-1 text-xs opacity-80">{tf(language, 'rtMoreWaiting', { n: more })}</p> : null}
    </section>
  );
}

function LeadsBlock({ r, language, activeBusinessId }: { r: BusinessResponseReport; language: Lang; activeBusinessId: number | null | undefined }) {
  const nl = r.newLeads;
  const tone = speedTone(nl.firstTouch.medianSeconds);
  return (
    <div className="mb-4" data-testid={`rt-leads-${r.businessId}`}>
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <h3 className="!font-sans text-sm font-semibold text-zinc-900">{t(language, 'rtLeadsTitle')}</h3>
        {nl.firstTouch.replies > 0 ? (
          <p className="text-right">
            <span className={`rounded-md px-2 py-0.5 text-base font-semibold tabular-nums ${TONE_BADGE[tone]}`}>{formatWait(nl.firstTouch.medianSeconds, language)}</span>
            <span className="ml-1.5 text-[11px] text-zinc-500">{t(language, 'rtMedian')}</span>
          </p>
        ) : null}
      </div>
      <Waiting
        title={t(language, 'rtLeadsWaitingTitle')}
        testId={`rt-leads-waiting-${r.businessId}`}
        language={language}
        activeBusinessId={activeBusinessId}
        rows={nl.waiting.map((w) => ({ key: String(w.leadId), name: w.name, last4: w.phoneLast4, phone: w.phone, channel: w.channel, businessId: r.businessId, businessName: r.name, since: w.createdAt, seconds: w.seconds, leadId: w.leadId, newLead: true }))}
      />
      {nl.created === 0 && nl.pending === 0 ? (
        <p className="rounded-lg border border-dashed border-zinc-300 px-3 py-3 text-center text-xs text-zinc-500">{t(language, 'rtLeadsNone')}</p>
      ) : (
        <dl className="grid grid-cols-2 gap-1.5 min-[480px]:grid-cols-4">
          <Stat label={t(language, 'rtLeadsCreated')} value={nl.created} />
          <Stat label={t(language, 'rtWithin5')} value={pct(nl.firstTouch.within5Percent)} />
          <Stat label={t(language, 'rtWithin15')} value={pct(nl.firstTouch.within15Percent)} />
          <Stat label={t(language, 'rtLeadsPending')} value={nl.pending} />
        </dl>
      )}
      {nl.managers.length > 0 ? (
        <div className="mt-3">
          <p className="mb-1 text-xs font-medium text-zinc-600">{t(language, 'rtLeadsManagers')}</p>
          <ul className="divide-y divide-zinc-100 rounded-lg border border-zinc-200">
            {[...nl.managers].sort((a, b) => (a.medianSeconds ?? Infinity) - (b.medianSeconds ?? Infinity)).map((m) => (
              <li key={m.userId} className="flex min-h-11 items-center gap-3 px-3 py-2">
                <span className="min-w-0 flex-1 break-words text-sm font-medium text-zinc-900 [overflow-wrap:anywhere]">{m.username}</span>
                <span className="shrink-0 text-[11px] text-zinc-500">{tf(language, 'rtLeadsTouches', { n: m.touches })}</span>
                <span className={`shrink-0 rounded-md px-2 py-0.5 text-sm font-semibold tabular-nums ${TONE_BADGE[speedTone(m.medianSeconds)]}`}>{formatWait(m.medianSeconds, language)}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <ul className="mt-2 space-y-1 text-xs text-zinc-500">
        {nl.closedWithoutContact > 0 ? <li>{tf(language, 'rtLeadsClosed', { n: nl.closedWithoutContact })}</li> : null}
        {nl.offShiftTouches > 0 ? <li>{tf(language, 'rtLeadsOffShift', { n: nl.offShiftTouches })}</li> : null}
      </ul>
    </div>
  );
}

function TextsBlock({ r, language, activeBusinessId, open, setOpen }: { r: BusinessResponseReport; language: Lang; activeBusinessId: number | null | undefined; open: number | null; setOpen: (id: number | null) => void }) {
  const data = r.texts;
  const rows = rankManagers(data.managers);
  const places = new Map<number, number>();
  rows.forEach((m) => { if (m.replies > 0) places.set(m.userId, places.size + 1); });
  const inferred = data.managers.reduce((n, m) => n + m.inferredReplies, 0);
  return (
    <div data-testid={`rt-texts-${r.businessId}`}>
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <h3 className="!font-sans text-sm font-semibold text-zinc-900">{t(language, 'rtTextsTitle')}</h3>
        {data.team.replies > 0 ? (
          <p className="text-right">
            <span className={`rounded-md px-2 py-0.5 text-base font-semibold tabular-nums ${TONE_BADGE[speedTone(data.team.medianSeconds)]}`}>{formatWait(data.team.medianSeconds, language)}</span>
            <span className="ml-1.5 text-[11px] text-zinc-500">{t(language, 'rtMedian')}</span>
          </p>
        ) : null}
      </div>
      <Waiting
        title={t(language, 'rtWaitingTitle')}
        testId={`rt-waiting-${r.businessId}`}
        language={language}
        total={data.unansweredNow}
        activeBusinessId={activeBusinessId}
        rows={data.waiting.map((w) => ({ key: w.phone, name: w.name, last4: w.phoneLast4, phone: w.phone, businessId: r.businessId, businessName: r.name, preview: w.preview, since: w.since, seconds: w.seconds, leadId: w.leadId, leadStatus: w.leadStatus, lastAutoKey: w.lastAutoKey, lastAutoAt: w.lastAutoAt }))}
      />
      {rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-zinc-300 px-3 py-3 text-center text-xs text-zinc-500" data-testid={`rt-empty-${r.businessId}`}>
          {tf(language, 'rtEmptyBusiness', { business: r.name })}
        </p>
      ) : (
        <>
          <div className="mb-3"><StatsGrid m={data.team} language={language} withWork={false} /></div>
          <ul className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {rows.map((m) => (
              <ManagerCard key={m.userId} m={m} place={places.get(m.userId) ?? null} open={open === m.userId} onToggle={() => setOpen(open === m.userId ? null : m.userId)} language={language} />
            ))}
          </ul>
        </>
      )}
      <ul className="mt-2 space-y-1 text-xs text-zinc-500">
        {inferred > 0 ? <li>{tf(language, 'rtInferred', { n: inferred })}</li> : null}
        {data.unknownReplies > 0 ? <li>{tf(language, 'rtUnknown', { n: data.unknownReplies })}</li> : null}
        {data.offShiftReplies > 0 ? <li>{tf(language, 'rtOffShift', { n: data.offShiftReplies })}</li> : null}
      </ul>
    </div>
  );
}

export default function ResponseTimesView({ language, activeBusinessId }: { language: Language | null; activeBusinessId?: number | null }) {
  const [period, setPeriod] = useState<Period>('7d');
  const [scope, setScope] = useState<number | 'all'>('all');
  const [data, setData] = useState<ResponseOverview | null>(null);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetch(`/api/metrics/overview?period=${period}`, { cache: 'no-store' })
      .then((res) => (res.ok ? (res.json() as Promise<ResponseOverview>) : Promise.reject(new Error(String(res.status)))))
      .then((body) => { if (alive) { setData(body); setError(false); } })
      .catch(() => { if (alive) setError(true); });
    return () => { alive = false; };
  }, [period]);

  const ready = data !== null && data.period === period;
  const shown = data ? data.businesses.filter((b) => scope === 'all' || b.businessId === scope) : [];
  const multi = (data?.businesses.length ?? 0) > 1;
  const nobodyWaiting = data ? data.total.unansweredNow === 0 && data.total.pendingLeads === 0 : false;

  return (
    <div className="min-w-0">
      <div role="tablist" className="mb-3 grid w-full grid-cols-4 gap-1 rounded-xl border border-zinc-200 bg-white p-1 sm:inline-grid sm:w-auto">
        {PERIODS.map((p) => (
          <button
            key={p.id}
            role="tab"
            aria-selected={period === p.id}
            onClick={() => setPeriod(p.id)}
            className={`min-h-11 whitespace-nowrap rounded-lg px-1 text-[13px] font-medium sm:px-4 sm:text-sm ${period === p.id ? 'bg-zinc-900 text-white' : 'text-zinc-600 hover:bg-zinc-50'}`}
          >
            {t(language, p.key)}
          </button>
        ))}
      </div>

      {error ? <p className="text-sm text-red-700">{t(language, 'rtLoadError')}</p> : null}
      {!ready && !error ? <Skeleton /> : null}

      {ready && data ? (
        <>
          {multi ? (
            <div role="group" aria-label={t(language, 'rtScopeLabel')} className="-mx-1 mb-4 flex gap-1.5 overflow-x-auto px-1 pb-1">
              {[{ id: 'all' as const, name: t(language, 'rtAllBusinesses') }, ...data.businesses.map((b) => ({ id: b.businessId, name: b.name }))].map((o) => (
                <button
                  key={String(o.id)}
                  type="button"
                  aria-pressed={scope === o.id}
                  onClick={() => setScope(o.id)}
                  className={`min-h-10 shrink-0 whitespace-nowrap rounded-full border px-3.5 text-[13px] font-medium ${scope === o.id ? 'border-zinc-900 bg-zinc-900 text-white' : 'border-zinc-200 bg-white text-zinc-700 hover:bg-zinc-50'}`}
                >
                  {o.name}
                </button>
              ))}
            </div>
          ) : null}

          {scope === 'all' ? (
            <div className="mb-4 grid grid-cols-1 gap-3 min-[560px]:grid-cols-2">
              <Kpi title={t(language, 'rtLeadsTitle')} stats={data.total.firstTouch} waiting={data.total.pendingLeads} waitingLabel={t(language, 'rtLeadsPending')} language={language} testId="rt-kpi-leads" />
              <Kpi title={t(language, 'rtTextsTitle')} stats={data.total.texts} waiting={data.total.unansweredNow} waitingLabel={t(language, 'rtLeadsPending')} language={language} testId="rt-kpi-texts" />
            </div>
          ) : null}
          {scope === 'all' && nobodyWaiting ? <p className="mb-4 text-xs text-emerald-700" data-testid="rt-all-clear">{t(language, 'rtAllClear')}</p> : null}

          <div className="space-y-4">
            {shown.map((b) => (
              <section key={b.businessId} className="rounded-2xl border border-zinc-200 bg-white p-3 sm:p-4" data-testid={`rt-business-${b.businessId}`}>
                <h2 className="mb-3 !font-sans text-base font-semibold text-zinc-900">{b.name}</h2>
                <LeadsBlock r={b} language={language} activeBusinessId={activeBusinessId} />
                <TextsBlock r={b} language={language} activeBusinessId={activeBusinessId} open={open !== null && open.startsWith(`${b.businessId}:`) ? Number(open.split(':')[1]) : null} setOpen={(id) => setOpen(id === null ? null : `${b.businessId}:${id}`)} />
              </section>
            ))}
          </div>
          <p className="mt-4 text-xs text-zinc-500">{t(language, 'rtLegend')}</p>
        </>
      ) : null}
    </div>
  );
}
