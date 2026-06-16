'use client';

import { useState } from 'react';
import type { SuspiciousBooking } from '../../../lib/types';

// Same URL constant used by AppointmentCell — opens the booking in Square's dashboard.
const SQUARE_RESERVATION = 'https://app.squareup.com/dashboard/appointments/calendar/reservations/';

const usd = (n: number | null) =>
  n == null ? '—' : n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

/** Render the customer's name as a link to the appointment in Square (falls back to a plain span). */
function CustomerLink({ b }: { b: SuspiciousBooking }) {
  const label = b.customerName ?? '(unnamed)';
  if (!b.bookingId) return <span className="font-medium text-zinc-700">{label}</span>;
  return (
    <a
      href={`${SQUARE_RESERVATION}${b.bookingId}`}
      target="_blank"
      rel="noopener noreferrer"
      title="Open this appointment in Square"
      className="font-medium text-blue-600 hover:underline"
    >
      {label}
    </a>
  );
}

/** Two-line note display (seller + customer); renders nothing when both are blank. */
function AppointmentNotes({ b }: { b: SuspiciousBooking }) {
  if (!b.sellerNote && !b.customerNote) return null;
  return (
    <div className="mt-1 space-y-0.5 rounded-md bg-zinc-50 px-2 py-1.5 text-xs text-zinc-600 ring-1 ring-zinc-200">
      {b.sellerNote && (
        <div><span className="font-medium text-zinc-500">Salon note:</span> {b.sellerNote}</div>
      )}
      {b.customerNote && (
        <div><span className="font-medium text-zinc-500">Customer note:</span> {b.customerNote}</div>
      )}
    </div>
  );
}

function fmtClearedAt(iso: string | null) {
  if (!iso) return null;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

export default function SuspiciousList({ initial }: { initial: SuspiciousBooking[] }) {
  const [items, setItems] = useState(initial);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState('');

  const uncleared = items.filter((i) => !i.cleared);
  const cleared   = items.filter((i) =>  i.cleared);

  async function clear(b: SuspiciousBooking) {
    setError('');
    setBusyId(b.bookingId);
    try {
      const res = await fetch(`/api/suspicious/${encodeURIComponent(b.bookingId)}/clear`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      if (!res.ok) throw new Error('Could not clear.');
      // Optimistic update: mark cleared locally with the current user as "you".
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

  async function undo(b: SuspiciousBooking) {
    setError('');
    setBusyId(b.bookingId);
    try {
      const res = await fetch(`/api/suspicious/${encodeURIComponent(b.bookingId)}/clear`, { method: 'DELETE' });
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
      <p data-testid="suspicious-empty" className="rounded-lg p-6 text-center text-sm text-zinc-500 ring-1 ring-zinc-200">
        No suspicious appointments for this period. 🎉
      </p>
    );
  }

  return (
    <div className="space-y-6">
      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Uncleared section */}
      <section data-testid="suspicious-uncleared-section">
        <h2 className="mb-2 text-sm font-medium text-zinc-700">To review · {uncleared.length}</h2>
        {uncleared.length === 0 ? (
          <p className="rounded-lg p-4 text-center text-sm text-zinc-400 ring-1 ring-zinc-200">
            All cleared — nothing left in this period.
          </p>
        ) : (
          <ul className="divide-y divide-zinc-100 rounded-lg ring-1 ring-zinc-200">
            {uncleared.map((b) => (
              <li key={b.bookingId} data-testid={`suspicious-row-${b.bookingId}`}
                  className="flex flex-col gap-2 px-3 py-2 text-sm sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0 flex-1">
                  <div className="font-medium">
                    {b.date} · <span className="font-normal text-zinc-500">{b.time}</span>
                  </div>
                  <div className="text-zinc-500">
                    <CustomerLink b={b} /> · {b.serviceName ?? '(service?)'} · {usd(b.gross)}
                  </div>
                  <AppointmentNotes b={b} />
                </div>
                <button
                  data-testid={`suspicious-clear-${b.bookingId}`}
                  onClick={() => clear(b)}
                  disabled={busyId === b.bookingId}
                  className="self-end rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50 sm:self-auto"
                >
                  {busyId === b.bookingId ? 'Clearing…' : 'Clear'}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Cleared section */}
      {cleared.length > 0 && (
        <section data-testid="suspicious-cleared-section">
          <h2 className="mb-2 text-sm font-medium text-zinc-500">Cleared earlier · {cleared.length}</h2>
          <ul className="divide-y divide-zinc-100 rounded-lg ring-1 ring-zinc-200 bg-zinc-50/40">
            {cleared.map((b) => (
              <li key={b.bookingId} data-testid={`suspicious-cleared-row-${b.bookingId}`}
                  className="flex flex-col gap-2 px-3 py-2 text-sm sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0 flex-1">
                  <div className="text-zinc-600">
                    {b.date} · <span className="text-zinc-400">{b.time}</span>
                  </div>
                  <div className="text-xs text-zinc-500">
                    <CustomerLink b={b} /> · {b.serviceName ?? '(service?)'} · {usd(b.gross)}
                  </div>
                  <AppointmentNotes b={b} />
                  <div className="mt-0.5 text-[11px] text-zinc-400">
                    cleared by {b.clearedBy ?? '?'} {fmtClearedAt(b.clearedAt) && `· ${fmtClearedAt(b.clearedAt)}`}
                  </div>
                </div>
                <button
                  data-testid={`suspicious-undo-${b.bookingId}`}
                  onClick={() => undo(b)}
                  disabled={busyId === b.bookingId}
                  className="self-end rounded border border-zinc-300 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 disabled:opacity-50 sm:self-auto"
                >
                  {busyId === b.bookingId ? 'Undoing…' : 'Undo'}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
