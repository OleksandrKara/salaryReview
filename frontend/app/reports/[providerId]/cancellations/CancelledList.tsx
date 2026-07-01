'use client';

import { useState } from 'react';
import type { CancelledAppointment, ServiceLine } from '../../../lib/types';

// Same URL constant used elsewhere — opens the booking in Square's dashboard.
const SQUARE_RESERVATION = 'https://app.squareup.com/dashboard/appointments/calendar/reservations/';

const usd = (n: number | null) =>
  n == null ? '—' : n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
const usdShort = (n: number | null) => (n == null ? null : `$${Math.round(n)}`);

/** Per-service chips — one per service variation on the booking, with the catalog price when known. */
function ServiceChips({ services, fallback, muted = false }: {
  services: ServiceLine[]; fallback: string | null; muted?: boolean;
}) {
  const chipClass = muted ? 'bg-white text-zinc-500 border-zinc-200' : 'bg-white text-zinc-700 border-zinc-300';
  const priceClass = muted ? 'text-zinc-400' : 'text-zinc-500';
  if (services.length === 0) {
    if (!fallback) return null;
    return (
      <div className="mt-2">
        <span className={`inline-block rounded-full border px-2.5 py-0.5 text-xs font-medium ${chipClass}`}>{fallback}</span>
      </div>
    );
  }
  return (
    <div className="mt-2 flex flex-wrap gap-1.5" data-testid="cancelled-service-chips">
      {services.map((s, i) => (
        <span key={i} className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium ${chipClass}`}>
          <span>{s.name ?? '(service?)'}</span>
          {s.gross != null && <span className={`tabular-nums font-normal ${priceClass}`}>{usdShort(s.gross)}</span>}
        </span>
      ))}
    </div>
  );
}

function CustomerLink({ b }: { b: CancelledAppointment }) {
  const label = b.customerName ?? '(unnamed)';
  if (!b.bookingId) return <span className="font-medium text-zinc-700">{label}</span>;
  return (
    <a href={`${SQUARE_RESERVATION}${b.bookingId}`} target="_blank" rel="noopener noreferrer"
       title="Open this appointment in Square" className="font-medium text-blue-600 hover:underline">
      {label}
    </a>
  );
}

function AppointmentNotes({ b }: { b: CancelledAppointment }) {
  if (!b.sellerNote && !b.customerNote) return null;
  return (
    <div className="mt-2 space-y-0.5 rounded-md border-l-4 border-rose-300 bg-rose-50/50 px-3 py-2 text-xs text-zinc-700">
      {b.sellerNote && <div><span className="font-medium text-rose-800">Salon note:</span> {b.sellerNote}</div>}
      {b.customerNote && <div><span className="font-medium text-rose-800">Customer note:</span> {b.customerNote}</div>}
    </div>
  );
}

function fmtClearedAt(iso: string | null) {
  if (!iso) return null;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export default function CancelledList({ initial }: { initial: CancelledAppointment[] }) {
  const [items, setItems] = useState(initial);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState('');

  const uncleared = items.filter((i) => !i.cleared);
  const cleared   = items.filter((i) =>  i.cleared);

  async function clear(b: CancelledAppointment) {
    setError('');
    setBusyId(b.bookingId);
    try {
      const res = await fetch(`/api/cancellations/${encodeURIComponent(b.bookingId)}/clear`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      if (!res.ok) throw new Error('Could not clear.');
      setItems((prev) => prev.map((x) =>
        x.bookingId === b.bookingId
          ? { ...x, cleared: true, clearedBy: 'you', clearedAt: new Date().toISOString(), clearedNote: null }
          : x));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not clear.');
    } finally {
      setBusyId(null);
    }
  }

  async function undo(b: CancelledAppointment) {
    setError('');
    setBusyId(b.bookingId);
    try {
      const res = await fetch(`/api/cancellations/${encodeURIComponent(b.bookingId)}/clear`, { method: 'DELETE' });
      if (!res.ok && res.status !== 204) throw new Error('Could not undo.');
      setItems((prev) => prev.map((x) =>
        x.bookingId === b.bookingId
          ? { ...x, cleared: false, clearedBy: null, clearedAt: null, clearedNote: null }
          : x));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not undo.');
    } finally {
      setBusyId(null);
    }
  }

  if (items.length === 0) {
    return (
      <p data-testid="cancelled-empty" className="rounded-lg p-6 text-center text-sm text-zinc-500 ring-1 ring-zinc-200">
        No cancelled appointments for this period. 🎉
      </p>
    );
  }

  return (
    <div className="space-y-6">
      {error && <p className="text-sm text-red-600">{error}</p>}

      <section data-testid="cancelled-uncleared-section">
        <h2 className="mb-2 text-sm font-medium text-zinc-700">To review · {uncleared.length}</h2>
        {uncleared.length === 0 ? (
          <p className="rounded-lg p-4 text-center text-sm text-zinc-400 ring-1 ring-zinc-200">
            All cleared — nothing left in this period.
          </p>
        ) : (
          <ul className="space-y-3">
            {uncleared.map((b) => (
              <li key={b.bookingId} data-testid={`cancelled-row-${b.bookingId}`}
                  className="flex flex-col gap-1 rounded-lg bg-white px-4 py-3 text-sm shadow-sm ring-1 ring-zinc-200">
                <div className="font-medium">
                  {b.date} · <span className="font-normal text-zinc-500">{b.time}</span>
                  <span className="ml-2 rounded bg-rose-50 px-1.5 py-0.5 text-[10px] font-medium text-rose-700 ring-1 ring-rose-300">cancelled</span>
                </div>
                <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-zinc-500">
                  <CustomerLink b={b} />
                  {b.gross != null && <span className="text-zinc-400">· {usd(b.gross)} value</span>}
                </div>
                <ServiceChips services={b.services} fallback={b.serviceName} />
                <AppointmentNotes b={b} />
                <div className="mt-2 flex items-center justify-end border-t border-zinc-100 pt-2">
                  <button
                    data-testid={`cancelled-clear-${b.bookingId}`}
                    onClick={() => clear(b)}
                    disabled={busyId === b.bookingId}
                    className="rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-800 disabled:opacity-50"
                  >
                    {busyId === b.bookingId ? 'Clearing…' : 'Clear'}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {cleared.length > 0 && (
        <section data-testid="cancelled-cleared-section">
          <h2 className="mb-2 text-sm font-medium text-zinc-500">Cleared earlier · {cleared.length}</h2>
          <ul className="space-y-3">
            {cleared.map((b) => (
              <li key={b.bookingId} data-testid={`cancelled-cleared-row-${b.bookingId}`}
                  className="flex flex-col gap-1 rounded-lg bg-zinc-50/60 px-4 py-3 text-sm shadow-sm ring-1 ring-zinc-200">
                <div className="text-zinc-600">
                  {b.date} · <span className="text-zinc-400">{b.time}</span>
                </div>
                <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-zinc-500">
                  <CustomerLink b={b} />
                  {b.gross != null && <span className="text-zinc-400">· {usd(b.gross)} value</span>}
                </div>
                <ServiceChips services={b.services} fallback={b.serviceName} muted />
                <AppointmentNotes b={b} />
                <div className="mt-1 text-[11px] text-zinc-400">
                  cleared by {b.clearedBy ?? '?'} {fmtClearedAt(b.clearedAt) && `· ${fmtClearedAt(b.clearedAt)}`}
                </div>
                <div className="mt-2 flex items-center justify-end border-t border-zinc-100 pt-2">
                  <button
                    data-testid={`cancelled-undo-${b.bookingId}`}
                    onClick={() => undo(b)}
                    disabled={busyId === b.bookingId}
                    className="rounded border border-zinc-300 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 disabled:opacity-50"
                  >
                    {busyId === b.bookingId ? 'Undoing…' : 'Undo'}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
