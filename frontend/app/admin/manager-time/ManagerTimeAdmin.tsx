'use client';

import { useState } from 'react';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import type { AdminTimesheet, AdminTimesheetRow, Language } from '../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
function fmtHM(minutes: number) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
const payOf = (rate: number | null, minutes: number) => (rate == null ? null : (rate * minutes) / 60);

export default function ManagerTimeAdmin({
  initial,
  language,
}: {
  initial: AdminTimesheet;
  language: Language | null;
}) {
  const [rows, setRows] = useState<AdminTimesheetRow[]>(initial.managers);
  const [rateInput, setRateInput] = useState<Record<number, string>>(
    () => Object.fromEntries(initial.managers.map((m) => [m.userId, m.usdPerHour != null ? String(m.usdPerHour) : '']))
  );
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');

  async function saveRate(userId: number) {
    const raw = (rateInput[userId] ?? '').trim();
    const val = Number(raw);
    if (raw === '' || !Number.isFinite(val) || val < 0) {
      setError('Enter a valid rate (0 or more).');
      return;
    }
    setError('');
    setBusyId(userId);
    try {
      await api.setManagerRate(userId, val);
      setRows((prev) => prev.map((r) => (r.userId === userId ? { ...r, usdPerHour: val, monthPay: payOf(val, r.monthMinutes) } : r)));
    } catch (e) {
      setError(e instanceof Error ? e.message.replace(/^\d+\s*/, '') : 'Could not save the rate.');
    } finally {
      setBusyId(null);
    }
  }

  if (rows.length === 0) {
    return <p className="rounded-lg p-6 text-center text-sm text-zinc-500 ring-1 ring-zinc-200">{t(language, 'timeNoManagers')}</p>;
  }

  const RateEditor = ({ r }: { r: AdminTimesheetRow }) => (
    <div className="flex items-center gap-1">
      <span className="text-zinc-400">$</span>
      <input
        type="number"
        min={0}
        step="0.5"
        inputMode="decimal"
        value={rateInput[r.userId] ?? ''}
        placeholder={t(language, 'timeSetRatePlaceholder')}
        onChange={(e) => setRateInput((p) => ({ ...p, [r.userId]: e.target.value }))}
        className="w-20 rounded-md border border-zinc-300 px-2 py-1 text-sm tabular-nums focus:border-zinc-500 focus:outline-none"
        data-testid={`rate-input-${r.userId}`}
      />
      <button
        onClick={() => saveRate(r.userId)}
        disabled={busyId === r.userId || (rateInput[r.userId] ?? '') === (r.usdPerHour != null ? String(r.usdPerHour) : '')}
        data-testid={`rate-save-${r.userId}`}
        className="rounded-md bg-zinc-900 px-2.5 py-1 text-xs font-medium text-white hover:bg-zinc-800 disabled:opacity-40"
      >
        {t(language, 'timeSave')}
      </button>
    </div>
  );

  const NameCell = ({ r }: { r: AdminTimesheetRow }) => (
    <span className="flex items-center gap-2">
      <span className="font-medium">{r.username}</span>
      {r.clockedIn && (
        <span
          title={t(language, 'timeClockedInNow')}
          className="inline-flex items-center gap-1 rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700 ring-1 ring-emerald-300"
        >
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-500" /> {t(language, 'timeClockedInNow')}
        </span>
      )}
    </span>
  );

  return (
    <div>
      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-red-200">{error}</p>}

      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {rows.map((r) => (
          <div key={r.userId} data-testid={`manager-card-${r.userId}`} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <NameCell r={r} />
            <dl className="mt-3 space-y-1 text-sm">
              <div className="flex justify-between font-medium"><dt>{t(language, 'timeMonthTotal')}</dt><dd className="tabular-nums">{fmtHM(r.monthMinutes)}{r.monthPay != null && <span className="ml-1 font-normal text-emerald-700">· {usd(r.monthPay)}</span>}</dd></div>
            </dl>
            <div className="mt-3 flex items-center justify-between">
              <span className="text-xs text-zinc-500">{t(language, 'timeRate')}</span>
              <RateEditor r={r} />
            </div>
          </div>
        ))}
      </div>

      {/* Desktop table */}
      <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">{t(language, 'timeManager')}</th>
              <th className="px-3 py-2 text-right">{t(language, 'timeMonthTotal')}</th>
              <th className="px-3 py-2">{t(language, 'timeRate')}</th>
              <th className="px-3 py-2 text-right">{t(language, 'timePay')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {rows.map((r) => (
              <tr key={r.userId} data-testid={`manager-row-${r.userId}`} className="hover:bg-zinc-50">
                <td className="px-3 py-2"><NameCell r={r} /></td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{fmtHM(r.monthMinutes)}</td>
                <td className="px-3 py-2"><RateEditor r={r} /></td>
                <td className="px-3 py-2 text-right font-medium tabular-nums text-emerald-700">
                  {r.monthPay != null ? usd(r.monthPay) : <span className="text-zinc-300">—</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
