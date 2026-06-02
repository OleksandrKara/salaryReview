'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import type { NoShowRow } from '../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function fmtDate(d: string) {
  const [, m, day] = d.split('-').map(Number);
  return `${MONTHS[(m - 1) % 12]} ${day}`;
}

// Owner/manager no-show table for the month. Detected fees auto-credit (no action needed); the buttons
// handle the exceptions — suppress a wrong auto-match, mark an off-signal fee as paid, or un-do either.
export default function NoShowFeesPanel({ year, month }: { year: number; month: number }) {
  const [rows, setRows] = useState<NoShowRow[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    api.listNoShowFees(year, month).then(setRows).catch(() => setRows([]));
  }, [year, month]);
  // Clear on month change so the spinner shows while the (Square-backed) fetch runs, rather than
  // briefly showing the previous month's rows. Override actions call load() directly and keep the table.
  useEffect(() => { setRows(null); load(); }, [load]);

  async function act(key: string, fn: () => Promise<void>) {
    setBusy(key);
    try { await fn(); load(); } finally { setBusy(null); }
  }

  if (rows === null) {
    return (
      <section className="mt-10">
        <h2 className="mb-1 text-sm font-semibold">No-shows</h2>
        <div className="flex items-center gap-3 rounded-lg px-4 py-6 text-sm text-zinc-500 ring-1 ring-zinc-200">
          <Spinner className="h-5 w-5 text-zinc-400" /> Loading no-shows…
        </div>
      </section>
    );
  }
  const credited = rows.filter((r) => r.state === 'CREDITED' || r.state === 'CONFIRMED');
  const total = credited.reduce((s, r) => s + (r.feeAmount ?? 0), 0);

  return (
    <section className="mt-10">
      <h2 className="mb-1 text-sm font-semibold">No-shows</h2>
      <p className="mb-3 text-xs text-zinc-500">
        No-show appointments this month. A paid $25 cancellation fee is auto-detected and credited to the
        provider in full — no action needed. Use the buttons only for exceptions.
      </p>
      {rows.length === 0 ? (
        <p className="rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">No no-shows this month.</p>
      ) : (
        <div className="overflow-hidden rounded-lg ring-1 ring-zinc-200">
          <table className="w-full text-sm">
            <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
              <tr>
                <th className="px-3 py-2">Provider</th>
                <th className="px-3 py-2">No-show</th>
                <th className="px-3 py-2">Customer</th>
                <th className="px-3 py-2">Fee</th>
                <th className="px-3 py-2 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {rows.map((r) => {
                const key = `${r.bookingId}-${r.providerId}`;
                return (
                  <tr key={key} className="hover:bg-zinc-50">
                    <td className="px-3 py-2 font-medium">{r.providerName}</td>
                    <td className="px-3 py-2 tabular-nums text-zinc-600">{fmtDate(r.noShowDate)}</td>
                    <td className="px-3 py-2 text-zinc-600">{r.customer ?? '—'}</td>
                    <td className="px-3 py-2"><FeeBadge row={r} /></td>
                    <td className="px-3 py-2 text-right">
                      <Action row={r} busy={busy === key} act={(fn) => act(key, fn)} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
            {total > 0 && (
              <tfoot className="border-t border-zinc-200 bg-zinc-50 text-xs font-medium">
                <tr>
                  <td className="px-3 py-2" colSpan={3}>No-show fees credited</td>
                  <td className="px-3 py-2 tabular-nums text-yellow-800" colSpan={2}>+{usd(total)}</td>
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}
    </section>
  );
}

function FeeBadge({ row }: { row: NoShowRow }) {
  if (row.state === 'CREDITED')
    return <Badge cls="bg-yellow-50 text-yellow-800 ring-yellow-300">fee paid +{usd(row.feeAmount ?? 25)}</Badge>;
  if (row.state === 'CONFIRMED')
    return <Badge cls="bg-yellow-50 text-yellow-800 ring-yellow-300">confirmed +{usd(row.feeAmount ?? 25)}</Badge>;
  if (row.state === 'SUPPRESSED')
    return <Badge cls="bg-zinc-100 text-zinc-500 ring-zinc-300">suppressed</Badge>;
  return <span className="text-xs text-zinc-400">no fee collected</span>;
}

function Badge({ cls, children }: { cls: string; children: React.ReactNode }) {
  return <span className={`rounded px-2 py-0.5 text-xs font-medium ring-1 ${cls}`}>{children}</span>;
}

function Action({ row, busy, act }: { row: NoShowRow; busy: boolean; act: (fn: () => Promise<void>) => void }) {
  const btn = 'text-xs text-blue-600 hover:underline disabled:opacity-40';
  if (row.state === 'CREDITED')
    return <button className={btn} disabled={busy} onClick={() => act(() => api.suppressNoShowFee(row.bookingId))}>Not a fee</button>;
  if (row.state === 'NO_FEE')
    return (
      <button className={btn} disabled={busy} onClick={() => act(() => api.confirmNoShowFee({
        bookingId: row.bookingId, providerId: row.providerId, amount: 25,
        feePaidDate: row.noShowDate, customerName: row.customer, noShowDate: row.noShowDate,
      }))}>Mark fee paid</button>
    );
  return <button className={btn} disabled={busy} onClick={() => act(() => api.clearNoShowFee(row.bookingId))}>Undo</button>;
}
