'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Language } from '../lib/types';
import { t, tf } from '../lib/i18n';
import VoidPanel from './VoidPanel';
import AdjustPanel from './AdjustPanel';
import { reasonLabel } from './BreakQuestions';
import { type DayView, type Pattern, type Summary, findingText, hhmm, hrs, tcFetch, usd } from '../lib/timecards';

const SEV = {
  INFO: 'bg-zinc-100 text-zinc-700',
  WARNING: 'bg-amber-100 text-amber-900',
  ALERT: 'bg-red-100 text-red-800',
} as const;

function DayCard({ d, owner, lang, onChange, onFixed, fixed }: { d: DayView; owner: boolean; lang: Language; onChange: () => void; onFixed?: (d: DayView) => void; fixed?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [voiding, setVoiding] = useState(false);
  const [adjusting, setAdjusting] = useState(false);
  const post = async (path: string, body: object) => {
    setBusy(true);
    setErr('');
    try {
      await tcFetch<DayView>(path, 'POST', body);
      onChange();
      return true;
    } catch (e) {
      setErr((e as Error).message);
      return false;
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
      <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-2 text-sm">
        <span>
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
        {d.mealWaiver && <span className="text-zinc-600"> · {t(lang, 'tcMealWaivedTag')}</span>}
        </span>
        {card && !adjusting && (d.source === 'SQUARE' || d.source === 'DRAFT') && (
          <button disabled={busy} onClick={() => setAdjusting(true)} className="min-h-[44px] w-full rounded border border-zinc-400 min-h-[44px] px-3 py-1.5 text-sm disabled:opacity-50 sm:w-auto">
            {t(lang, 'tcAdjust')}
          </button>
        )}
      </div>
      {(d.attentionReasons?.length ?? 0) > 0 || d.needsAttention ? (
        <div className="mt-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <div className="font-medium">{t(lang, 'tcAttn')}</div>
          <ul className="mt-1 list-disc pl-5 text-xs">
            {d.attentionReasons.map((r) => <li key={r} className="break-words">{t(lang, `tcAttn${r}` as 'tcAttnMEAL_MISSING')}</li>)}
          </ul>
        </div>
      ) : null}
      {(d.workerAnswers ?? []).filter((a) => a.reason).map((a) => (
        <div key={`${a.kind}${a.index}`} className="mt-1 break-words text-sm text-zinc-700">
          {t(lang, 'tcWorkerSaid')} ({t(lang, a.kind === 'MEAL' ? 'tcBqMeal' : 'tcBqRest')}): {reasonLabel(lang, a.reason)}
        </div>
      ))}
      {fixed && d.status === 'NEEDS_REVIEW' && <div className="mt-2 rounded bg-amber-50 px-2 py-1.5 text-sm text-amber-900">{t(lang, 'tcFixedNotice')}</div>}
      {d.note && <div className="mt-1 text-sm italic text-zinc-600">{d.note}</div>}
      {d.findings.length > 0 && (
        <ul className="mt-2 flex flex-col gap-1">
          {d.findings.map((f, i) => (
            <li key={i} className={`rounded px-2 py-1 text-xs ${SEV[f.severity]}`}>
              {findingText(lang, f)}
            </li>
          ))}
        </ul>
      )}
      <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center [&>button]:w-full sm:[&>button]:w-auto">
        {d.status !== 'APPROVED' && (
          <>
            {workerDone ? (
              <button disabled={busy} onClick={() => post('approve', { ...base, onBehalf: false })} className="rounded bg-zinc-900 min-h-[44px] px-3 py-1.5 text-sm text-white disabled:opacity-50">
                {t(lang, 'tcApprove')}
              </button>
            ) : (
              <button disabled={busy} onClick={() => post('approve', { ...base, onBehalf: true })} className="rounded border border-zinc-400 min-h-[44px] px-3 py-1.5 text-sm disabled:opacity-50">
                {t(lang, 'tcApproveBehalf')}
              </button>
            )}
          </>
        )}
        {d.status === 'APPROVED' && (
          <button disabled={busy} onClick={() => post('reopen', base)} className="rounded border border-zinc-400 min-h-[44px] px-3 py-1.5 text-sm disabled:opacity-50">
            {t(lang, 'tcReopen')}
          </button>
        )}
        {d.source === 'DRAFT' && !voiding && (
          <button disabled={busy} onClick={() => setVoiding(true)} className="rounded border border-red-300 min-h-[44px] px-3 py-1.5 text-sm text-red-700 disabled:opacity-50">
            {t(lang, 'tcVoid')}
          </button>
        )}
        <label className="flex min-h-[44px] items-center gap-2 text-sm">
          <input className="h-5 w-5 shrink-0" type="checkbox" checked={d.restNotProvided} disabled={busy} onChange={(e) => post('rest-not-provided', { ...base, notProvided: e.target.checked })} />
          {t(lang, 'tcRestNot')}
        </label>
      </div>
      {d.source === 'SQUARE' && <div className="mt-2 text-xs text-zinc-500">{t(lang, 'tcVoidSquare')}</div>}
      {adjusting && card && (
        <AdjustPanel lang={lang} date={d.date} start={card.start} end={card.end} fromSquare={d.source === 'SQUARE'} meals={card.breaks} busy={busy} err={err}
          onClose={() => setAdjusting(false)}
          onConfirm={async (start, end, note, breaks) => {
            if (await post('adjust', { ...base, start, end, note, breaks })) {
              setAdjusting(false);
              onFixed?.(d);
            }
          }} />
      )}
      {voiding && (
        <VoidPanel lang={lang} hint={t(lang, 'tcVoidHint')} busy={busy} err={err} onClose={() => setVoiding(false)}
          onConfirm={(reason) => post('void', { ...base, reason })} />
      )}
      {!voiding && !adjusting && err && <div className="mt-2 text-sm text-red-700">{err}</div>}
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
                <th className="py-1">{t(lang, 'tcColName')}</th><th>{t(lang, 'tcColTotal')}</th><th>{t(lang, 'tcColReg')}</th><th>{t(lang, 'tcColOt')}</th><th>{t(lang, 'tcColDt')}</th>
                <th>{t(lang, 'tcColWages')}</th><th>{t(lang, 'tcColPremiums')}</th><th>{t(lang, 'tcColTips')}</th><th>{t(lang, 'tcColSum')}</th><th>{t(lang, 'tcUnapproved')}</th><th>{t(lang, 'tcColCommission')}</th>
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
              {data.providers.length === 0 && <tr><td colSpan={11} className="py-3 text-zinc-500">{t(lang, 'tcNoW2')}</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

type Filter = 'pending' | 'approved' | 'attention' | 'all';

function PatternBanner({ lang, patterns }: { lang: Language; patterns: Pattern[] }) {
  const hot = patterns.filter((p) => p.level !== 'NONE');
  if (hot.length === 0) return null;
  return (
    <section aria-label={t(lang, 'tcPatternTitle')} className="mb-4 rounded-lg border border-zinc-200 bg-white p-3">
      <h2 className="mb-2 text-sm font-medium">{t(lang, 'tcPatternTitle')}</h2>
      <ul className="flex flex-wrap gap-2">
        {hot.map((p) => (
          <li key={p.providerId} className={`min-w-0 max-w-full break-words rounded-full px-3 py-1.5 text-sm ring-1 ${p.level === 'RED' ? 'bg-red-50 text-red-800 ring-red-300' : 'bg-amber-50 text-amber-900 ring-amber-300'}`}>
            <span className="font-medium">{p.name}</span> · {tf(lang, 'tcPatternDays', { n: String(p.flaggedDays) })}
          </li>
        ))}
      </ul>
    </section>
  );
}

export default function TimecardsApproval({ role, language, businessName }: { role: string; language: Language; businessName: string }) {
  const owner = role === 'OWNER';
  const [tab, setTab] = useState<'queue' | 'payroll'>('queue');
  const [rows, setRows] = useState<DayView[] | null>(null);
  const [err, setErr] = useState('');
  const [filter, setFilter] = useState<Filter>('pending');
  const [period, setPeriod] = useState(30);
  const [emp, setEmp] = useState('');
  const [date, setDate] = useState('');
  const [fixedKeys, setFixedKeys] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const [patterns, setPatterns] = useState<Pattern[]>([]);
  const keyOf = (d: DayView) => `${d.providerId}-${d.date}`;
  const onFixed = (d: DayView) => {
    setFixedKeys((k) => [...k, keyOf(d)]);
    setNotice(`${d.providerName} ${d.date}: ${t(language, 'tcFixedNotice')}`);
    setFilter('pending');
  };
  const load = useCallback(() => {
    tcFetch<DayView[]>(`queue?days=${period}&scope=all`).then(setRows).catch((e) => setErr(e.message));
  }, [period]);
  useEffect(load, [load]);
  useEffect(() => {
    tcFetch<Pattern[]>('patterns').then(setPatterns).catch(() => setPatterns([]));
  }, [rows]);
  const employees = useMemo(() => {
    const m = new Map<number, string>();
    rows?.forEach((d) => m.set(d.providerId, d.providerName));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [rows]);
  const scoped = useMemo(
    () => (rows ?? []).filter((d) => (!emp || String(d.providerId) === emp) && (!date || d.date === date)),
    [rows, emp, date],
  );
  const live = scoped.filter((d) => d.source !== 'VOIDED');
  const pendingN = live.filter((d) => d.status !== 'APPROVED').length;
  const approvedN = live.filter((d) => d.status === 'APPROVED').length;
  const attentionN = live.filter((d) => d.needsAttention).length;
  const shown = live.filter((d) => filter === 'all' || (filter === 'attention' ? d.needsAttention : (filter === 'approved') === (d.status === 'APPROVED')));
  const voided = scoped.filter((d) => d.source === 'VOIDED');
  const chip = (on: boolean) => `min-h-[44px] rounded px-3 py-1.5 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 focus-visible:ring-offset-2 ${on ? 'bg-zinc-900 text-white' : 'border border-zinc-300'}`;
  const sel = 'min-h-[44px] w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm';
  return (
    <div>
      {owner && (
        <div className="mb-4 flex gap-2 text-sm">
          {(['queue', 'payroll'] as const).map((k) => (
            <button key={k} onClick={() => setTab(k)} className={chip(tab === k)}>
              {t(language, k === 'queue' ? 'tcQueue' : 'tcPayroll')}
            </button>
          ))}
        </div>
      )}
      {tab === 'payroll' && owner ? (
        <PayrollTab lang={language} />
      ) : (
        <>
          <PatternBanner lang={language} patterns={patterns} />
          <div className="mb-3 flex flex-wrap gap-2">
            {([['pending', 'tcTabPending', pendingN], ['attention', 'tcTabAttention', attentionN], ['approved', 'tcTabApproved', approvedN], ['all', 'tcTabAll', pendingN + approvedN]] as const).map(([k, label, n]) => (
              <button key={k} onClick={() => setFilter(k)} aria-pressed={filter === k} className={chip(filter === k)}>
                {t(language, label)} ({n})
              </button>
            ))}
          </div>
          <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-4">
            <select aria-label={t(language, 'tcPeriod')} value={period} onChange={(e) => setPeriod(Number(e.target.value))} className={sel}>
              <option value={14}>{t(language, 'tcDays14')}</option>
              <option value={30}>{t(language, 'tcDays30')}</option>
              <option value={60}>{t(language, 'tcDays60')}</option>
            </select>
            <select aria-label="employee" value={emp} onChange={(e) => setEmp(e.target.value)} className={sel}>
              <option value="">{t(language, 'tcAllEmployees')}</option>
              {employees.map(([id, name]) => (
                <option key={id} value={id}>{name}</option>
              ))}
            </select>
            <input aria-label={t(language, 'tcDateSearch')} type="date" value={date} onChange={(e) => setDate(e.target.value)} className={sel} />
            <button onClick={() => { setEmp(''); setDate(''); setFilter('pending'); }} className="min-h-[44px] rounded border border-zinc-300 px-3 py-1.5 text-sm">
              {t(language, 'tcClearFilters')}
            </button>
          </div>
          {notice && <div role="status" className="mb-3 rounded bg-amber-50 px-3 py-2 text-sm text-amber-900">{notice}</div>}
          {err && <div className="text-sm text-red-700">{err}</div>}
          {rows && shown.length === 0 && <p className="text-sm text-zinc-500">{t(language, 'tcNothing')}{businessName ? ` ${tf(language, 'tcViewingBusiness', { name: businessName })}` : ''}</p>}
          <ul className="flex flex-col gap-3">
            {shown.map((d) => <DayCard key={keyOf(d)} d={d} owner={owner} lang={language} onChange={load} onFixed={onFixed} fixed={fixedKeys.includes(keyOf(d))} />)}
          </ul>
          {voided.length > 0 && (
            <details className="mt-4">
              <summary className="min-h-[44px] cursor-pointer text-sm text-zinc-600">{t(language, 'tcVoidedList')} ({voided.length})</summary>
              <ul className="mt-2 flex flex-col gap-2">
                {voided.map((d) => <DayCard key={`${d.providerId}-${d.date}`} d={d} owner={owner} lang={language} onChange={load} />)}
              </ul>
            </details>
          )}
        </>
      )}
    </div>
  );
}
