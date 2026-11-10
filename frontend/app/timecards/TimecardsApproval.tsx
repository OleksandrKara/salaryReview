'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Language } from '../lib/types';
import { t, tf } from '../lib/i18n';
import VoidPanel from './VoidPanel';
import { type DayView, type Summary, hhmm, hrs, tcFetch, usd } from '../lib/timecards';

const SEV = {
  INFO: 'bg-zinc-100 text-zinc-700',
  WARNING: 'bg-amber-100 text-amber-900',
  ALERT: 'bg-red-100 text-red-800',
} as const;

function DayCard({ d, owner, lang, onChange }: { d: DayView; owner: boolean; lang: Language; onChange: () => void }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [voiding, setVoiding] = useState(false);
  const post = async (path: string, body: object) => {
    setBusy(true);
    setErr('');
    try {
      await tcFetch<DayView>(path, 'POST', body);
      onChange();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const base = { date: d.date, providerId: d.providerId };
  const workerDone = d.source === 'SQUARE' || d.status === 'WORKER_CONFIRMED' || d.status === 'CONFIRMED_PENDING_WRITE';
  const card = d.timecards[0];
  if (d.source === 'VOIDED') {
    return (
      <li className="flex flex-col gap-2 rounded-lg border border-dashed border-zinc-300 bg-zinc-50 p-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0 text-sm text-zinc-500">
          <div className="font-medium text-zinc-600">
            {d.providerName} <span className="font-normal">{d.date}</span>
            <span className="ml-2 rounded bg-zinc-200 px-2 py-0.5 text-xs font-normal text-zinc-700">{t(lang, 'tcStatusVOIDED')}</span>
          </div>
          {d.voidReason && <div className="mt-1 break-words text-xs">{t(lang, 'tcVoidedReason')}: {d.voidReason}</div>}
          {err && <div className="mt-1 text-xs text-red-700">{err}</div>}
        </div>
        <button disabled={busy} onClick={() => post('restore', base)} className="w-full rounded-md border border-zinc-400 bg-white px-4 py-2 text-sm disabled:opacity-50 sm:w-auto">
          {t(lang, 'tcRestore')}
        </button>
      </li>
    );
  }
  return (
    <li className="rounded-lg border border-zinc-200 bg-white p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="font-medium">
          {d.providerName} <span className="text-zinc-500">{d.date}</span>
        </div>
        <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs">
          {t(lang, `tcStatus${d.status}` as 'tcStatusAPPROVED')}
          {d.changedInSquare ? ' (changed in Square)' : ''}
        </span>
      </div>
      <div className="mt-1 text-sm text-zinc-600">
        {t(lang, d.source === 'SQUARE' ? 'tcSourceSQUARE' : 'tcSourceDRAFT')}
        {card ? ` · ${hhmm(card.start)}-${hhmm(card.end)}` : ''}
        {d.source === 'DRAFT' && d.appointments.length === 0 && (
          <span className="ml-2 rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800">{t(lang, 'tcManualBadge')}</span>
        )}
        {card?.breaks.length ? ` · ${card.breaks.map((b) => `${b.kind === 'UNPAID' ? 'meal' : 'rest'} ${hhmm(b.start)}-${hhmm(b.end)}`).join(', ')}` : ''}
      </div>
      <div className="mt-1 text-sm">
        {t(lang, 'tcPaid')}: {hrs(d.paidMinutes)} {t(lang, 'tcHours')}
        {d.overtimeMinutes + d.doubleTimeMinutes > 0 && (
          <span className="text-red-700"> · OT {hrs(d.overtimeMinutes)} / DT {hrs(d.doubleTimeMinutes)}</span>
        )}
        {owner && d.payCents != null && (
          <span className="text-zinc-600">
            {' '}
            · {usd(d.payCents)}
            {d.premiumCents ? ` + ${usd(d.premiumCents)} premiums` : ''}
          </span>
        )}
        {d.mealWaiver && <span className="text-zinc-600"> · meal waived</span>}
      </div>
      {d.note && <div className="mt-1 text-sm italic text-zinc-600">{d.note}</div>}
      {d.findings.length > 0 && (
        <ul className="mt-2 flex flex-col gap-1">
          {d.findings.map((f, i) => (
            <li key={i} className={`rounded px-2 py-1 text-xs ${SEV[f.severity]}`}>
              {f.message}
            </li>
          ))}
        </ul>
      )}
      <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center [&>button]:w-full sm:[&>button]:w-auto">
        {d.status !== 'APPROVED' && (
          <>
            {workerDone ? (
              <button disabled={busy} onClick={() => post('approve', { ...base, onBehalf: false })} className="rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-50">
                {t(lang, 'tcApprove')}
              </button>
            ) : (
              <button disabled={busy} onClick={() => post('approve', { ...base, onBehalf: true })} className="rounded border border-zinc-400 px-3 py-1.5 text-sm disabled:opacity-50">
                {t(lang, 'tcApproveBehalf')}
              </button>
            )}
          </>
        )}
        {d.status === 'APPROVED' && (
          <button disabled={busy} onClick={() => post('reopen', base)} className="rounded border border-zinc-400 px-3 py-1.5 text-sm disabled:opacity-50">
            {t(lang, 'tcReopen')}
          </button>
        )}
        {d.source === 'DRAFT' && !voiding && (
          <button disabled={busy} onClick={() => setVoiding(true)} className="rounded border border-red-300 px-3 py-1.5 text-sm text-red-700 disabled:opacity-50">
            {t(lang, 'tcVoid')}
          </button>
        )}
        <label className="flex items-center gap-1 text-sm">
          <input type="checkbox" checked={d.restNotProvided} disabled={busy} onChange={(e) => post('rest-not-provided', { ...base, notProvided: e.target.checked })} />
          {t(lang, 'tcRestNot')}
        </label>
      </div>
      {d.source === 'SQUARE' && <div className="mt-2 text-xs text-zinc-500">{t(lang, 'tcVoidSquare')}</div>}
      {voiding && (
        <VoidPanel lang={lang} hint={t(lang, 'tcVoidHint')} busy={busy} err={err} onClose={() => setVoiding(false)}
          onConfirm={(reason) => post('void', { ...base, reason })} />
      )}
      {!voiding && err && <div className="mt-2 text-sm text-red-700">{err}</div>}
    </li>
  );
}

function PayrollTab({ lang }: { lang: Language }) {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [half, setHalf] = useState(now.getDate() <= 15 ? 1 : 2);
  const [data, setData] = useState<Summary | null>(null);
  const [err, setErr] = useState('');
  const q = `year=${year}&month=${month}&half=${half}`;
  useEffect(() => {
    setErr('');
    tcFetch<Summary>(`admin/summary?${q}`).then(setData).catch((e) => setErr(e.message));
  }, [q]);
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <input type="number" value={year} onChange={(e) => setYear(Number(e.target.value))} className="w-24 rounded border border-zinc-300 px-2 py-1" />
        <select value={month} onChange={(e) => setMonth(Number(e.target.value))} className="rounded border border-zinc-300 px-2 py-1">
          {Array.from({ length: 12 }, (_, i) => (
            <option key={i} value={i + 1}>{i + 1}</option>
          ))}
        </select>
        <select value={half} onChange={(e) => setHalf(Number(e.target.value))} className="rounded border border-zinc-300 px-2 py-1">
          <option value={1}>1-15</option>
          <option value={2}>16-end</option>
        </select>
        <a href={`/api/timecards/admin/summary.csv?${q}`} className="rounded border border-zinc-400 px-3 py-1">{t(lang, 'tcCsv')}</a>
      </div>
      {err && <div className="text-sm text-red-700">{err}</div>}
      {data && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[56rem] text-sm">
            <thead>
              <tr className="border-b text-left text-xs text-zinc-500">
                <th className="py-1">Name</th><th>Total h</th><th>Reg h</th><th>OT 1.5x h</th><th>DT 2x h</th>
                <th>Wages</th><th>Premiums</th><th>Tips</th><th>Total</th><th>{t(lang, 'tcUnapproved')}</th><th>Commission (private)</th>
              </tr>
            </thead>
            <tbody>
              {data.providers.map((p) => (
                <tr key={p.providerId} className="border-b">
                  <td className="py-1">{p.name}</td>
                  <td>{hrs(p.totalMinutes)}</td><td>{hrs(p.regularMinutes)}</td><td>{hrs(p.overtimeMinutes)}</td><td>{hrs(p.doubleTimeMinutes)}</td>
                  <td>{usd(p.regularPayCents + p.overtimePayCents + p.doubleTimePayCents)}</td>
                  <td>{usd(p.mealPremiumCents + p.restPremiumCents + p.splitShiftPremiumCents)}</td>
                  <td>{usd(p.tipsCents)}</td><td className="font-medium">{usd(p.totalToPayCents)}</td>
                  <td className={p.unapprovedDays ? 'text-red-700' : ''}>{p.unapprovedDays}</td>
                  <td className="text-zinc-500">{usd(p.commissionComparisonCents)}</td>
                </tr>
              ))}
              {data.providers.length === 0 && <tr><td colSpan={11} className="py-3 text-zinc-500">No W2 workers.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function TimecardsApproval({ role, language, businessName }: { role: string; language: Language; businessName: string }) {
  const owner = role === 'OWNER';
  const [tab, setTab] = useState<'queue' | 'payroll'>('queue');
  const [rows, setRows] = useState<DayView[] | null>(null);
  const [err, setErr] = useState('');
  const load = useCallback(() => {
    tcFetch<DayView[]>('queue?days=21').then(setRows).catch((e) => setErr(e.message));
  }, []);
  useEffect(load, [load]);
  return (
    <div>
      {owner && (
        <div className="mb-4 flex gap-2 text-sm">
          {(['queue', 'payroll'] as const).map((k) => (
            <button key={k} onClick={() => setTab(k)} className={`rounded px-3 py-1.5 ${tab === k ? 'bg-zinc-900 text-white' : 'border border-zinc-300'}`}>
              {t(language, k === 'queue' ? 'tcQueue' : 'tcPayroll')}
            </button>
          ))}
        </div>
      )}
      {tab === 'payroll' && owner ? (
        <PayrollTab lang={language} />
      ) : (
        <>
          {err && <div className="text-sm text-red-700">{err}</div>}
          {rows && rows.filter((d) => d.source !== 'VOIDED').length === 0 && <p className="text-sm text-zinc-500">{t(language, 'tcNothing')}{businessName ? ` ${tf(language, 'tcViewingBusiness', { name: businessName })}` : ''}</p>}
          <ul className="flex flex-col gap-3">
            {rows?.filter((d) => d.source !== 'VOIDED').map((d) => <DayCard key={`${d.providerId}-${d.date}`} d={d} owner={owner} lang={language} onChange={load} />)}
          </ul>
          {rows && rows.some((d) => d.source === 'VOIDED') && (
            <details className="mt-4">
              <summary className="cursor-pointer text-sm text-zinc-600">{t(language, 'tcVoidedList')} ({rows.filter((d) => d.source === 'VOIDED').length})</summary>
              <ul className="mt-2 flex flex-col gap-2">
                {rows.filter((d) => d.source === 'VOIDED').map((d) => <DayCard key={`${d.providerId}-${d.date}`} d={d} owner={owner} lang={language} onChange={load} />)}
              </ul>
            </details>
          )}
        </>
      )}
    </div>
  );
}
