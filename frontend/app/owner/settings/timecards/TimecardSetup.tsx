'use client';

import { useEffect, useState } from 'react';
import type { Language } from '../../../lib/types';
import { t } from '../../../lib/i18n';
import { type Approver, type ProviderSetup, tcFetch } from '../../../lib/timecards';

function ProviderCard({ p, candidates, lang, onSaved }: { p: ProviderSetup; candidates: Approver[]; lang: Language; onSaved: (p: ProviderSetup) => void }) {
  const [workerType, setWorkerType] = useState(p.workerType);
  const [rate, setRate] = useState(p.hourlyRateCents == null ? '' : (p.hourlyRateCents / 100).toFixed(2));
  const [from, setFrom] = useState(p.rateEffectiveFrom ?? new Date().toISOString().slice(0, 10));
  const [phone, setPhone] = useState(p.mobilePhone ?? '');
  const [alerts, setAlerts] = useState(p.alertsEnabled);
  const [sms, setSms] = useState(p.smsEnabled);
  const [auto, setAuto] = useState(p.payrollAutomationEnabled);
  const [cmp, setCmp] = useState(p.showCommissionComparisonToProvider);
  const [approvers, setApprovers] = useState<number[]>(p.approvers.map((a) => a.userId));
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setBusy(true);
    setMsg('');
    try {
      const cents = rate.trim() === '' ? null : Math.round(Number(rate) * 100);
      const rateChanged = cents != null && (cents !== p.hourlyRateCents || from !== p.rateEffectiveFrom);
      const saved = await tcFetch<ProviderSetup>(`admin/providers/${p.providerId}`, 'PUT', {
        workerType,
        showCommissionComparisonToProvider: cmp,
        ...(rateChanged ? { hourlyRateCents: cents, rateEffectiveFrom: from } : {}),
        ...(p.hourlyRateCents != null || rateChanged ? { mobilePhone: phone, alertsEnabled: alerts, smsEnabled: sms, payrollAutomationEnabled: auto } : {}),
        approverUserIds: approvers,
      });
      onSaved(saved);
      setMsg(t(lang, 'tcSaved'));
    } catch (e) {
      setMsg((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const importPhone = async () => {
    setMsg('');
    try {
      const saved = await tcFetch<ProviderSetup>(`admin/providers/${p.providerId}/import-phone`, 'POST', {});
      setPhone(saved.mobilePhone ?? '');
      onSaved(saved);
    } catch (e) {
      setMsg((e as Error).message);
    }
  };
  const field = 'rounded border border-zinc-300 px-2 py-1 text-sm';
  return (
    <li className="rounded-lg border border-zinc-200 bg-white p-4">
      <div className="mb-3 font-medium">{p.name}{!p.active && <span className="text-zinc-500"> (inactive)</span>}</div>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">{t(lang, 'tcWorkerType')}
          <select className={field} value={workerType} onChange={(e) => setWorkerType(e.target.value as 'W2' | '1099')}>
            <option value="1099">1099 (commission)</option>
            <option value="W2">W2 (hourly)</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">{t(lang, 'tcRate')}
          <input className={field} inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">{t(lang, 'tcRateFrom')}
          <input className={field} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">{t(lang, 'tcPhone')}
          <span className="flex gap-2">
            <input className={`${field} flex-1`} value={phone} onChange={(e) => setPhone(e.target.value)} />
            {p.squareLinked && p.hourlyRateCents != null && (
              <button type="button" onClick={importPhone} className="rounded border border-zinc-400 px-2 text-xs">{t(lang, 'tcImportPhone')}</button>
            )}
          </span>
        </label>
      </div>
      <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-sm">
        <label className="flex items-center gap-1"><input type="checkbox" checked={alerts} onChange={(e) => setAlerts(e.target.checked)} />{t(lang, 'tcAlerts')}</label>
        <label className="flex items-center gap-1"><input type="checkbox" checked={sms} onChange={(e) => setSms(e.target.checked)} />{t(lang, 'tcSms')}</label>
        <label className="flex items-center gap-1"><input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} />{t(lang, 'tcPayrollAuto')}</label>
        <label className="flex items-center gap-1"><input type="checkbox" checked={cmp} onChange={(e) => setCmp(e.target.checked)} />{t(lang, 'tcShowComparison')}</label>
      </div>
      <fieldset className="mt-3 text-sm">
        <legend className="mb-1 text-zinc-600">{t(lang, 'tcApprovers')}</legend>
        <div className="flex flex-wrap gap-x-5 gap-y-1">
          {candidates.map((c) => (
            <label key={c.userId} className="flex items-center gap-1">
              <input type="checkbox" checked={approvers.includes(c.userId)} onChange={(e) => setApprovers((cur) => (e.target.checked ? [...cur, c.userId] : cur.filter((x) => x !== c.userId)))} />
              {c.email ?? c.username} ({c.role.toLowerCase()})
            </label>
          ))}
        </div>
      </fieldset>
      <div className="mt-3 flex items-center gap-3">
        <button disabled={busy} onClick={save} className="rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-50">{t(lang, 'tcSave')}</button>
        {msg && <span className="text-sm text-zinc-600">{msg}</span>}
      </div>
    </li>
  );
}

export default function TimecardSetup({ language }: { language: Language }) {
  const [rows, setRows] = useState<ProviderSetup[] | null>(null);
  const [candidates, setCandidates] = useState<Approver[]>([]);
  const [err, setErr] = useState('');
  useEffect(() => {
    Promise.all([tcFetch<ProviderSetup[]>('admin/providers'), tcFetch<Approver[]>('admin/approver-candidates')])
      .then(([r, c]) => { setRows(r); setCandidates(c); })
      .catch((e) => setErr(e.message));
  }, []);
  return (
    <div>
      {err && <div className="text-sm text-red-700">{err}</div>}
      <ul className="flex flex-col gap-3">
        {rows?.map((p) => (
          <ProviderCard key={`${p.providerId}-${p.workerType}-${p.hourlyRateCents}`} p={p} candidates={candidates} lang={language}
            onSaved={(s) => setRows((cur) => cur && cur.map((x) => (x.providerId === s.providerId ? s : x)))} />
        ))}
      </ul>
    </div>
  );
}
