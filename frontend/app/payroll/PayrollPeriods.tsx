'use client';

import { useEffect, useMemo, useState } from 'react';
import type { Language } from '../lib/types';
import { t } from '../lib/i18n';
import { ProviderSummary, Summary, hrs, tcFetch, usd } from '../lib/timecards';

function shiftHalf(y: number, m: number, h: number, delta: number) {
  let idx = y * 24 + (m - 1) * 2 + (h - 1) + delta;
  const year = Math.floor(idx / 24);
  idx -= year * 24;
  return { y: year, m: Math.floor(idx / 2) + 1, h: (idx % 2) + 1 };
}

const wagesOf = (p: ProviderSummary) => p.regularPayCents + p.overtimePayCents + p.doubleTimePayCents;
const premOf = (p: ProviderSummary) => p.mealPremiumCents + p.restPremiumCents + p.splitShiftPremiumCents;

function Card({ p, lang, owner }: { p: ProviderSummary; lang: Language; owner: boolean }) {
  const cell = (label: string, value: string) => (
    <div><dt className="text-xs text-zinc-500">{label}</dt><dd className="tabular-nums">{value}</dd></div>
  );
  return (
    <li className="rounded-xl border border-zinc-200 bg-white p-3">
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="min-w-0 font-semibold">{p.name}</h2>
        <div className="text-lg font-semibold tabular-nums">{usd(p.totalToPayCents)}</div>
      </div>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
        {cell(t(lang, 'tcColTotal'), hrs(p.totalMinutes))}
        {cell(t(lang, 'tcColReg'), hrs(p.regularMinutes))}
        {cell(t(lang, 'tcColOt'), hrs(p.overtimeMinutes))}
        {cell(t(lang, 'tcColDt'), hrs(p.doubleTimeMinutes))}
        {cell(t(lang, 'tcColWages'), usd(wagesOf(p)))}
        {cell(t(lang, 'tcColPremiums'), usd(premOf(p)))}
        {cell(t(lang, 'tcColTips'), usd(p.tipsCents))}
        {owner && p.commissionComparisonCents != null && cell(t(lang, 'tcColCommission'), usd(p.commissionComparisonCents))}
      </dl>
      {p.unapprovedDays > 0 && <div className="mt-2 text-sm text-red-700">{t(lang, 'tcUnapproved')}: {p.unapprovedDays}</div>}
    </li>
  );
}

export default function PayrollPeriods({ lang, owner }: { lang: Language; owner: boolean }) {
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
  const total = useMemo(() => data?.providers.reduce((s, p) => s + p.totalToPayCents, 0) ?? 0, [data]);
  const btn = 'min-h-[44px] min-w-[44px] rounded border border-zinc-300 bg-white px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900';
  const th = 'px-2 py-1.5 text-right font-normal';
  const td = 'px-2 py-1.5 text-right tabular-nums';
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button type="button" aria-label={t(lang, 'pbPrev')} className={btn} onClick={() => setPer((p) => shiftHalf(p.y, p.m, p.h, -1))}>‹</button>
        <div className="min-w-0 flex-1 text-center text-sm font-medium" aria-live="polite">{data ? `${data.from} - ${data.to}` : t(lang, 'pbLoading')}</div>
        <button type="button" aria-label={t(lang, 'pbNext')} className={btn} onClick={() => setPer((p) => shiftHalf(p.y, p.m, p.h, 1))}>›</button>
        <a href={`/api/timecards/payroll/summary.csv?${q}`} className={`${btn} inline-flex items-center`}>{t(lang, 'tcCsv')}</a>
      </div>
      {data && (
        <div className="mb-3 flex items-baseline justify-between rounded-lg bg-zinc-100 px-3 py-2 text-sm">
          <span className="text-zinc-600">{t(lang, 'pbGrandTotal')}</span>
          <span className="text-lg font-semibold tabular-nums">{usd(total)}</span>
        </div>
      )}
      {cur?.err && <div className="text-sm text-red-700">{cur.err}</div>}
      {data && !data.complete && <div className="mb-3 rounded border border-amber-300 bg-amber-50 p-2 text-sm text-amber-900">{t(lang, 'pbIncomplete')}</div>}
      {data && data.providers.length === 0 && <p className="text-sm text-zinc-500">{t(lang, 'tcNoW2')}</p>}
      {data && data.providers.length > 0 && (
        <>
          <ul className="space-y-2 lg:hidden">
            {data.providers.map((p) => <Card key={p.providerId} p={p} lang={lang} owner={owner} />)}
          </ul>
          <div className="hidden overflow-x-auto rounded-lg border border-zinc-200 bg-white lg:block">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-xs text-zinc-500">
                  <th className="px-2 py-1.5 text-left font-normal">{t(lang, 'tcColName')}</th>
                  <th className={th}>{t(lang, 'tcColTotal')}</th><th className={th}>{t(lang, 'tcColReg')}</th><th className={th}>{t(lang, 'tcColOt')}</th><th className={th}>{t(lang, 'tcColDt')}</th>
                  <th className={th}>{t(lang, 'tcColWages')}</th><th className={th}>{t(lang, 'tcColPremiums')}</th><th className={th}>{t(lang, 'tcColTips')}</th>
                  <th className={th}>{t(lang, 'tcColSum')}</th><th className={th}>{t(lang, 'tcUnapproved')}</th>
                  {owner && <th className={th}>{t(lang, 'tcColCommission')}</th>}
                </tr>
              </thead>
              <tbody>
                {data.providers.map((p) => (
                  <tr key={p.providerId} className="border-b">
                    <td className="px-2 py-1.5">{p.name}</td>
                    <td className={td}>{hrs(p.totalMinutes)}</td><td className={td}>{hrs(p.regularMinutes)}</td><td className={td}>{hrs(p.overtimeMinutes)}</td><td className={td}>{hrs(p.doubleTimeMinutes)}</td>
                    <td className={td}>{usd(wagesOf(p))}</td><td className={td}>{usd(premOf(p))}</td><td className={td}>{usd(p.tipsCents)}</td>
                    <td className={`${td} font-medium`}>{usd(p.totalToPayCents)}</td>
                    <td className={`${td} ${p.unapprovedDays ? 'text-red-700' : ''}`}>{p.unapprovedDays}</td>
                    {owner && <td className={`${td} text-zinc-500`}>{usd(p.commissionComparisonCents)}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
