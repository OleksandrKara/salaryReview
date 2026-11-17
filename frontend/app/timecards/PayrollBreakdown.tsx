'use client';

import { useEffect, useMemo, useState } from 'react';
import type { Language } from '../lib/types';
import { t } from '../lib/i18n';
import { DayLine, ProviderSummary, Summary, hhmm, hrs, tcFetch, usd } from '../lib/timecards';

function shiftHalf(y: number, m: number, h: number, delta: number) {
  let idx = y * 24 + (m - 1) * 2 + (h - 1) + delta;
  const year = Math.floor(idx / 24);
  idx -= year * 24;
  return { y: year, m: Math.floor(idx / 2) + 1, h: (idx % 2) + 1 };
}

function breakText(d: DayLine, lang: Language) {
  return d.unpaidGapMinutes > 0 ? `${t(lang, 'pbBreak')} ${d.unpaidGapMinutes} ${t(lang, 'pbMin')}` : t(lang, 'pbNoBreak');
}

function dayLabel(date: string, lang: Language) {
  const d = new Date(`${date}T12:00:00`);
  return d.toLocaleDateString(lang === 'RU' ? 'ru-RU' : 'en-US', { weekday: 'short', month: 'short', day: 'numeric' });
}

function Row({ label, value, strong, hint }: { label: string; value: string; strong?: boolean; hint?: string }) {
  return (
    <div className={`flex items-baseline justify-between gap-3 py-1 ${strong ? 'border-t border-zinc-200 pt-2 font-semibold' : ''}`}>
      <dt className="min-w-0 text-zinc-600">
        {label}
        {hint && <span className="block text-xs text-zinc-500">{hint}</span>}
      </dt>
      <dd className="shrink-0 tabular-nums">{value}</dd>
    </div>
  );
}

function DayDetails({ d, lang }: { d: DayLine; lang: Language }) {
  const rate = d.rateCents;
  const hint = (min: number, mult: string) => `${hrs(min)} h x ${usd(rate)}${mult}`;
  return (
    <dl className="mt-2 text-sm">
      {d.inAt && d.outAt && <Row label={t(lang, 'pbShift')} value={`${hhmm(d.inAt)} - ${hhmm(d.outAt)}`} />}
      <Row label={t(lang, 'pbUnpaidGap')} value={d.unpaidGapMinutes > 0 ? `${d.unpaidGapMinutes} ${t(lang, 'pbMin')}` : t(lang, 'pbNoBreak')} />
      <Row label={t(lang, 'pbRate')} value={usd(rate)} />
      <Row label={t(lang, 'pbRegular')} value={usd(d.regularPayCents)} hint={hint(d.regularMinutes, '')} />
      {d.overtimeMinutes > 0 && <Row label={t(lang, 'pbOvertime')} value={usd(d.overtimePayCents)} hint={hint(d.overtimeMinutes, ' x 1.5')} />}
      {d.doubleTimeMinutes > 0 && <Row label={t(lang, 'pbDoubleTime')} value={usd(d.doubleTimePayCents)} hint={hint(d.doubleTimeMinutes, ' x 2')} />}
      {d.mealPremiumCents > 0 && <Row label={t(lang, 'pbMealPremium')} value={usd(d.mealPremiumCents)} />}
      {d.restPremiumCents > 0 && <Row label={t(lang, 'pbRestPremium')} value={usd(d.restPremiumCents)} />}
      {d.splitShiftPremiumCents > 0 && <Row label={t(lang, 'pbSplitPremium')} value={usd(d.splitShiftPremiumCents)} />}
      <Row label={t(lang, 'pbDayTotal')} value={usd(d.dayTotalCents)} strong />
      {d.notes.length > 0 && (
        <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-zinc-600">
          {d.notes.map((n, i) => <li key={i} className="break-words">{n}</li>)}
        </ul>
      )}
    </dl>
  );
}

function DayCard({ d, lang, open, onToggle }: { d: DayLine; lang: Language; open: boolean; onToggle: () => void }) {
  return (
    <li className="rounded-lg border border-zinc-200 bg-white">
      <button type="button" onClick={onToggle} aria-expanded={open}
        className="flex min-h-[48px] w-full items-center justify-between gap-3 px-3 py-2 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900">
        <span className="min-w-0">
          <span className="block font-medium">{dayLabel(d.date, lang)}</span>
          <span className="block text-xs text-zinc-500">
            {d.inAt && d.outAt ? `${hhmm(d.inAt)}-${hhmm(d.outAt)} · ` : ''}{hrs(d.paidMinutes)} h · {breakText(d, lang)}
            {!d.approved && <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-amber-900">{t(lang, 'pbNotApproved')}</span>}
          </span>
        </span>
        <span className="flex shrink-0 items-center gap-2">
          <span className="font-semibold tabular-nums">{usd(d.dayTotalCents)}</span>
          <span aria-hidden className={`text-zinc-400 transition-transform ${open ? 'rotate-90' : ''}`}>›</span>
        </span>
      </button>
      {open && <div className="border-t border-zinc-100 px-3 pb-3"><DayDetails d={d} lang={lang} /></div>}
    </li>
  );
}

function DayTable({ days, lang, open, toggle }: { days: DayLine[]; lang: Language; open: Set<string>; toggle: (k: string) => void }) {
  const th = 'px-2 py-1.5 text-right font-normal';
  return (
    <table className="hidden w-full text-sm md:table">
      <thead>
        <tr className="border-b text-xs text-zinc-500">
          <th className="px-2 py-1.5 text-left font-normal">{t(lang, 'pbDays')}</th>
          <th className={th}>{t(lang, 'pbShift')}</th>
          <th className={th}>{t(lang, 'pbHours')}</th>
          <th className={th}>{t(lang, 'pbRate')}</th>
          <th className={th}>{t(lang, 'pbRegular')}</th>
          <th className={th}>{t(lang, 'pbOvertime')}</th>
          <th className={th}>{t(lang, 'pbDoubleTime')}</th>
          <th className={th}>{t(lang, 'tcColPremiums')}</th>
          <th className={th}>{t(lang, 'pbDayTotal')}</th>
        </tr>
      </thead>
      <tbody>
        {days.map((d) => {
          const k = d.date;
          const prem = d.mealPremiumCents + d.restPremiumCents + d.splitShiftPremiumCents;
          return (
            <FragmentRow key={k}>
              <tr className="cursor-pointer border-b hover:bg-zinc-50" onClick={() => toggle(k)}>
                <td className="px-2 py-1.5">
                  <button type="button" aria-expanded={open.has(k)} className="text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900">
                    {dayLabel(d.date, lang)}
                    {!d.approved && <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-900">{t(lang, 'pbNotApproved')}</span>}
                  </button>
                </td>
                <td className="px-2 py-1.5 text-right tabular-nums">{d.inAt && d.outAt ? `${hhmm(d.inAt)}-${hhmm(d.outAt)}` : ''}<span className="block text-xs text-zinc-500">{breakText(d, lang)}</span></td>
                <td className="px-2 py-1.5 text-right tabular-nums">{hrs(d.paidMinutes)}</td>
                <td className="px-2 py-1.5 text-right tabular-nums">{usd(d.rateCents)}</td>
                <td className="px-2 py-1.5 text-right tabular-nums">{usd(d.regularPayCents)}</td>
                <td className="px-2 py-1.5 text-right tabular-nums">{d.overtimeMinutes ? usd(d.overtimePayCents) : ''}</td>
                <td className="px-2 py-1.5 text-right tabular-nums">{d.doubleTimeMinutes ? usd(d.doubleTimePayCents) : ''}</td>
                <td className="px-2 py-1.5 text-right tabular-nums">{prem ? usd(prem) : ''}</td>
                <td className="px-2 py-1.5 text-right font-medium tabular-nums">{usd(d.dayTotalCents)}</td>
              </tr>
              {open.has(k) && (
                <tr className="border-b bg-zinc-50"><td colSpan={9} className="px-4 pb-3"><DayDetails d={d} lang={lang} /></td></tr>
              )}
            </FragmentRow>
          );
        })}
      </tbody>
    </table>
  );
}

function FragmentRow({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}

function ProviderCard({ p, lang, owner }: { p: ProviderSummary; lang: Language; owner: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (k: string) => setOpen((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const wages = p.regularPayCents + p.overtimePayCents + p.doubleTimePayCents;
  const prem = p.mealPremiumCents + p.restPremiumCents + p.splitShiftPremiumCents;
  const allOpen = p.days.length > 0 && open.size === p.days.length;
  return (
    <section className="rounded-xl border border-zinc-200 bg-zinc-50 p-3 sm:p-4" aria-label={p.name}>
      <button type="button" onClick={() => setExpanded((v) => !v)} aria-expanded={expanded}
        className="flex min-h-[48px] w-full items-center justify-between gap-3 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900">
        <h2 className="min-w-0 text-lg font-semibold">{p.name}</h2>
        <span className="flex shrink-0 items-center gap-2">
          <span className="text-xl font-semibold tabular-nums">{usd(p.totalToPayCents)}</span>
          <span aria-hidden className={`text-zinc-400 transition-transform ${expanded ? 'rotate-90' : ''}`}>›</span>
        </span>
      </button>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 text-sm sm:grid-cols-4">
        <div><dt className="text-xs text-zinc-500">{t(lang, 'pbHours')}</dt><dd className="tabular-nums">{hrs(p.totalMinutes)}</dd></div>
        <div><dt className="text-xs text-zinc-500">{t(lang, 'pbWages')}</dt><dd className="tabular-nums">{usd(wages)}</dd></div>
        <div><dt className="text-xs text-zinc-500">{t(lang, 'pbPremiums')}</dt><dd className="tabular-nums">{usd(prem)}</dd></div>
        <div><dt className="text-xs text-zinc-500">{t(lang, 'pbTips')}</dt><dd className="tabular-nums">{usd(p.tipsCents)}</dd></div>
      </dl>
      {p.unapprovedDays > 0 && <div className="mt-2 text-sm text-red-700">{t(lang, 'tcUnapproved')}: {p.unapprovedDays}</div>}
      {owner && p.commissionComparisonCents != null && (
        <div className="mt-1 text-xs text-zinc-500">{t(lang, 'tcColCommission')}: {usd(p.commissionComparisonCents)}</div>
      )}
      {!expanded ? null : p.days.length === 0 ? (
        <p className="mt-3 text-sm text-zinc-500">{t(lang, 'pbNoDays')}</p>
      ) : (
        <>
          <div className="mt-3 flex items-center justify-between">
            <h3 className="text-sm font-medium">{t(lang, 'pbDays')}</h3>
            <button type="button" onClick={() => setOpen(allOpen ? new Set() : new Set(p.days.map((d) => d.date)))}
              className="min-h-[44px] rounded px-2 text-sm text-zinc-700 underline focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900">
              {t(lang, allOpen ? 'pbCollapseAll' : 'pbExpandAll')}
            </button>
          </div>
          <ul className="space-y-2 md:hidden">
            {p.days.map((d) => <DayCard key={d.date} d={d} lang={lang} open={open.has(d.date)} onToggle={() => toggle(d.date)} />)}
          </ul>
          <div className="hidden overflow-x-auto rounded-lg border border-zinc-200 bg-white md:block">
            <DayTable days={p.days} lang={lang} open={open} toggle={toggle} />
          </div>
          <p className="mt-2 text-xs text-zinc-500">{t(lang, 'pbTipsNote')}</p>
        </>
      )}
    </section>
  );
}

export default function PayrollBreakdown({ lang, owner }: { lang: Language; owner: boolean }) {
  const now = new Date();
  const [per, setPer] = useState({ y: now.getFullYear(), m: now.getMonth() + 1, h: now.getDate() <= 15 ? 1 : 2 });
  const [res, setRes] = useState<{ q: string; data?: Summary; err?: string } | null>(null);
  const q = `year=${per.y}&month=${per.m}&half=${per.h}`;
  useEffect(() => {
    tcFetch<Summary>(`payroll/summary?${q}`)
      .then((data) => setRes({ q, data }))
      .catch((e) => setRes({ q, err: e.message }));
  }, [q]);
  const cur = res?.q === q ? res : null;
  const data = cur?.data ?? null;
  const err = cur?.err ?? '';
  const total = useMemo(() => data?.providers.reduce((s, p) => s + p.totalToPayCents, 0) ?? 0, [data]);
  const consistent = useMemo(
    () => data?.providers.every((p) => p.days.reduce((s, d) => s + d.dayTotalCents, 0) === p.regularPayCents + p.overtimePayCents + p.doubleTimePayCents + p.mealPremiumCents + p.restPremiumCents + p.splitShiftPremiumCents) ?? true,
    [data],
  );
  const btn = 'min-h-[44px] min-w-[44px] rounded border border-zinc-300 bg-white px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900';
  return (
    <div>
      <div className="sticky top-0 z-10 -mx-3 mb-3 border-b border-zinc-200 bg-white/95 px-3 py-2 backdrop-blur sm:mx-0 sm:rounded-lg sm:border">
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" aria-label={t(lang, 'pbPrev')} className={btn} onClick={() => setPer((p) => shiftHalf(p.y, p.m, p.h, -1))}>‹</button>
          <div className="min-w-0 flex-1 text-center text-sm font-medium" aria-live="polite">
            {data ? `${data.from} - ${data.to}` : t(lang, 'pbLoading')}
          </div>
          <button type="button" aria-label={t(lang, 'pbNext')} className={btn} onClick={() => setPer((p) => shiftHalf(p.y, p.m, p.h, 1))}>›</button>
          <a href={`/api/timecards/payroll/summary.csv?${q}`} className={`${btn} inline-flex items-center`}>{t(lang, 'tcCsv')}</a>
        </div>
        {data && (
          <div className="mt-1 flex items-baseline justify-between text-sm">
            <span className="text-zinc-600">{t(lang, 'pbGrandTotal')}</span>
            <span className="text-lg font-semibold tabular-nums">{usd(total)}</span>
          </div>
        )}
      </div>
      {err && <div className="text-sm text-red-700">{err}</div>}
      {data && !data.complete && <div className="mb-3 rounded border border-amber-300 bg-amber-50 p-2 text-sm text-amber-900">{t(lang, 'pbIncomplete')}</div>}
      {data && !consistent && <div className="mb-3 rounded border border-red-300 bg-red-50 p-2 text-sm text-red-800">{t(lang, 'pbSumMismatch')}</div>}
      {data && data.providers.length === 0 && <p className="text-sm text-zinc-500">{t(lang, 'tcNoW2')}</p>}
      <div className="space-y-4">
        {data?.providers.map((p) => <ProviderCard key={p.providerId} p={p} lang={lang} owner={owner} />)}
      </div>
    </div>
  );
}
