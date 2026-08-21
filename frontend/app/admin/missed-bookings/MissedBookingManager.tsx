'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { t } from '../../lib/i18n';
import type { Language, MissedBooking } from '../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function formatDate(iso: string): string {
  // Parsed as a plain calendar date (no time component), not through `new Date(iso)` directly —
  // that treats a bare "YYYY-MM-DD" as UTC midnight, which renders as the *previous* day in any
  // timezone west of UTC (this salon is in the US) — a manager logging "today" would see yesterday.
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function formatTime(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number);
  const period = h >= 12 ? 'PM' : 'AM';
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, '0')} ${period}`;
}

function monthKey(iso: string): string {
  return iso.slice(0, 7); // "YYYY-MM"
}

function monthLabel(key: string): string {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}

function weekdayIndex(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).getDay();
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-zinc-600">{label}</span>
      {children}
    </label>
  );
}

const input = 'w-full rounded border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none sm:w-auto';

function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// Manager-side quick log ("we had nowhere to book this customer") plus the owner-facing analysis
// (total missed revenue, by month, by day of week) it exists to feed — see backend V121. All
// analysis is computed client-side from the already-loaded list; this salon's expected volume
// (a handful of entries a week at most) makes a dedicated aggregation endpoint unnecessary.
export default function MissedBookingManager({
  initialMissedBookings,
  language = null,
}: {
  initialMissedBookings: MissedBooking[];
  language?: Language | null;
}) {
  const router = useRouter();
  const [rows, setRows] = useState(initialMissedBookings);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [requestedDate, setRequestedDate] = useState(todayIso());
  const [requestedTime, setRequestedTime] = useState('');
  const [estimatedRevenue, setEstimatedRevenue] = useState('');
  const [serviceName, setServiceName] = useState('');

  async function refresh() {
    setRows(await fetch('/api/missed-bookings', { cache: 'no-store' }).then((r) => r.json()));
    router.refresh();
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = await fetch('/api/missed-bookings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestedDate,
          requestedTime: requestedTime || null,
          estimatedRevenue: Number(estimatedRevenue),
          serviceName: serviceName || null,
        }),
      });
      if (!res.ok) throw new Error(t(language, 'missedBookingErrCreate'));
      setRequestedDate(todayIso());
      setRequestedTime('');
      setEstimatedRevenue('');
      setServiceName('');
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : t(language, 'missedBookingErrCreateFallback'));
    } finally {
      setBusy(false);
    }
  }

  async function remove(row: MissedBooking) {
    if (!window.confirm(t(language, 'missedBookingConfirmDelete'))) return;
    setError('');
    const res = await fetch(`/api/missed-bookings/${row.id}`, { method: 'DELETE' });
    if (!res.ok && res.status !== 204) {
      setError(t(language, 'missedBookingErrDelete'));
      return;
    }
    await refresh();
  }

  const totalRevenue = useMemo(() => rows.reduce((sum, r) => sum + r.estimatedRevenue, 0), [rows]);
  const avgRevenue = rows.length > 0 ? totalRevenue / rows.length : 0;

  const byMonth = useMemo(() => {
    const map = new Map<string, { count: number; revenue: number }>();
    for (const r of rows) {
      const key = monthKey(r.requestedDate);
      const entry = map.get(key) ?? { count: 0, revenue: 0 };
      entry.count += 1;
      entry.revenue += r.estimatedRevenue;
      map.set(key, entry);
    }
    return [...map.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1));
  }, [rows]);

  const byWeekday = useMemo(() => {
    const buckets = WEEKDAYS.map(() => ({ count: 0, revenue: 0 }));
    for (const r of rows) {
      const idx = weekdayIndex(r.requestedDate);
      buckets[idx].count += 1;
      buckets[idx].revenue += r.estimatedRevenue;
    }
    return buckets;
  }, [rows]);

  return (
    <div className="flex flex-col gap-6">
      {/* Quick-add form — one row of the fields a manager actually has in hand in the moment. */}
      <form onSubmit={create} data-testid="missed-booking-form" className="rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
          <Field label={t(language, 'missedBookingDate')}>
            <input
              type="date"
              data-testid="missed-booking-date-input"
              value={requestedDate}
              onChange={(e) => setRequestedDate(e.target.value)}
              required
              className={input}
            />
          </Field>
          <Field label={t(language, 'missedBookingTimeOptional')}>
            <input
              type="time"
              data-testid="missed-booking-time-input"
              value={requestedTime}
              onChange={(e) => setRequestedTime(e.target.value)}
              className={input}
            />
          </Field>
          <Field label={t(language, 'missedBookingRevenue')}>
            <input
              type="number"
              step="0.01"
              min="0.01"
              data-testid="missed-booking-revenue-input"
              value={estimatedRevenue}
              onChange={(e) => setEstimatedRevenue(e.target.value)}
              required
              className={`${input} sm:w-28`}
            />
          </Field>
          <Field label={t(language, 'missedBookingServiceOptional')}>
            <input
              data-testid="missed-booking-service-input"
              value={serviceName}
              onChange={(e) => setServiceName(e.target.value)}
              placeholder={t(language, 'missedBookingServicePlaceholder')}
              className={`${input} sm:w-48`}
            />
          </Field>
          <button
            type="submit"
            disabled={busy}
            data-testid="missed-booking-submit"
            className="rounded bg-zinc-900 px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50 sm:mb-px"
          >
            {busy ? t(language, 'missedBookingAdding') : t(language, 'missedBookingAdd')}
          </button>
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      </form>

      {/* Analysis — the whole point of logging these: is demand consistently outrunning capacity
          enough to justify hiring another provider? */}
      <div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <div className="rounded-xl p-4 ring-1 ring-zinc-200">
            <div data-testid="missed-booking-total-revenue" className="text-2xl font-semibold text-zinc-900">{usd(totalRevenue)}</div>
            <div className="text-xs text-zinc-500">{t(language, 'missedBookingTotalRevenue')}</div>
          </div>
          <div className="rounded-xl p-4 ring-1 ring-zinc-200">
            <div data-testid="missed-booking-total-count" className="text-2xl font-semibold text-zinc-900">{rows.length}</div>
            <div className="text-xs text-zinc-500">{t(language, 'missedBookingTotalCount')}</div>
          </div>
          <div className="col-span-2 rounded-xl p-4 ring-1 ring-zinc-200 sm:col-span-1">
            <div className="text-2xl font-semibold text-zinc-900">{usd(avgRevenue)}</div>
            <div className="text-xs text-zinc-500">{t(language, 'missedBookingAvgRevenue')}</div>
          </div>
        </div>

        {rows.length > 0 && (
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <h2 className="mb-2 text-sm font-semibold text-zinc-700">{t(language, 'missedBookingByMonth')}</h2>
              <div className="overflow-hidden rounded-xl ring-1 ring-zinc-200">
                <table className="w-full text-sm">
                  <tbody className="divide-y divide-zinc-100">
                    {byMonth.map(([key, v]) => (
                      <tr key={key}>
                        <td className="px-3 py-1.5 text-zinc-600">{monthLabel(key)}</td>
                        <td className="px-3 py-1.5 text-right text-zinc-500">{v.count}</td>
                        <td className="px-3 py-1.5 text-right font-medium tabular-nums">{usd(v.revenue)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
            <div>
              <h2 className="mb-2 text-sm font-semibold text-zinc-700">{t(language, 'missedBookingByWeekday')}</h2>
              <div className="overflow-hidden rounded-xl ring-1 ring-zinc-200">
                <table className="w-full text-sm">
                  <tbody className="divide-y divide-zinc-100">
                    {byWeekday.map((v, i) => (
                      <tr key={i}>
                        <td className="px-3 py-1.5 text-zinc-600">{t(language, `missedBookingWeekday${WEEKDAYS[i]}` as Parameters<typeof t>[1])}</td>
                        <td className="px-3 py-1.5 text-right text-zinc-500">{v.count}</td>
                        <td className="px-3 py-1.5 text-right font-medium tabular-nums">{usd(v.revenue)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Mobile: cards */}
      <div data-testid="missed-booking-list" className="flex flex-col gap-3 sm:hidden">
        {rows.length === 0 && (
          <p className="py-4 text-center text-sm text-zinc-400">{t(language, 'missedBookingNone')}</p>
        )}
        {rows.map((r) => (
          <div key={r.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-medium text-zinc-800">
                  {formatDate(r.requestedDate)}
                  {r.requestedTime && <span className="text-zinc-500"> · {formatTime(r.requestedTime)}</span>}
                </p>
                {r.serviceName && <p className="text-xs text-zinc-400">{r.serviceName}</p>}
                {r.createdBy && <p className="text-xs text-zinc-400">{r.createdBy}</p>}
              </div>
              <p className="font-semibold tabular-nums text-zinc-800">{usd(r.estimatedRevenue)}</p>
            </div>
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <button data-testid={`missed-booking-delete-${r.id}`} onClick={() => remove(r)} className="text-xs text-red-500 hover:text-red-700">
                {t(language, 'missedBookingDelete')}
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Desktop: table */}
      <div data-testid="missed-booking-table" className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">{t(language, 'missedBookingDate')}</th>
              <th className="px-3 py-2">{t(language, 'missedBookingColTime')}</th>
              <th className="px-3 py-2 text-right">{t(language, 'missedBookingRevenue')}</th>
              <th className="px-3 py-2">{t(language, 'missedBookingColService')}</th>
              <th className="px-3 py-2">{t(language, 'missedBookingColLoggedBy')}</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {rows.map((r) => (
              <tr key={r.id} className="hover:bg-zinc-50">
                <td className="px-3 py-2 tabular-nums text-zinc-600">{formatDate(r.requestedDate)}</td>
                <td className="px-3 py-2 tabular-nums text-zinc-500">{r.requestedTime ? formatTime(r.requestedTime) : '—'}</td>
                <td className="px-3 py-2 text-right tabular-nums font-medium">{usd(r.estimatedRevenue)}</td>
                <td className="px-3 py-2 text-zinc-500">{r.serviceName ?? '—'}</td>
                <td className="px-3 py-2 text-zinc-500">{r.createdBy ?? '—'}</td>
                <td className="px-3 py-2 text-right">
                  <button data-testid={`missed-booking-delete-${r.id}`} onClick={() => remove(r)} className="text-xs text-red-500 hover:text-red-700">
                    {t(language, 'missedBookingDelete')}
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-4 text-center text-zinc-400">
                  {t(language, 'missedBookingNone')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
