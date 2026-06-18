'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import type {
  ServiceLine,
  SuspiciousBooking,
  TriageClassification,
  TriageResult,
} from '../../../lib/types';
import ExplainButton from './ExplainButton';
import TriageBadge from './TriageBadge';
import TriageResultDisplay from './TriageResult';

// Same URL constant used by AppointmentCell — opens the booking in Square's dashboard.
const SQUARE_RESERVATION = 'https://app.squareup.com/dashboard/appointments/calendar/reservations/';

const usd = (n: number | null) =>
  n == null ? '—' : n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

/** Short whole-dollar formatter for the per-service chips ("$80", not "$80.00"). */
const usdShort = (n: number | null) => (n == null ? null : `$${Math.round(n)}`);

/**
 * Per-service chip list. One chip per service variation on the booking, showing the full service
 * name (no truncation — long names are valid info) and the catalog price when known. Wraps
 * naturally on mobile via flex-wrap. Styling matches the project's existing chip pattern
 * ({@code rounded bg-zinc-100 ring-zinc-300}, see reports/page.tsx) so chips feel native and
 * distinct from the bordered note cards.
 */
function ServiceChips({
  services,
  fallback,
  muted = false,
}: {
  services: ServiceLine[];
  fallback: string | null;
  muted?: boolean;
}) {
  const chipClass = muted
    ? 'bg-zinc-50 text-zinc-500 ring-zinc-300'
    : 'bg-zinc-100 text-zinc-700 ring-zinc-300';
  const priceClass = muted ? 'text-zinc-400' : 'text-zinc-500';

  if (services.length === 0) {
    if (!fallback) return null;
    return (
      <div className="mt-1.5">
        <span
          data-testid="service-chip-fallback"
          className={`inline-block rounded px-2 py-0.5 text-xs font-medium ring-1 ${chipClass}`}
        >
          {fallback}
        </span>
      </div>
    );
  }

  return (
    <div className="mt-1.5 flex flex-wrap gap-1.5" data-testid="service-chips">
      {services.map((s, i) => (
        <span
          key={i}
          className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ring-1 ${chipClass}`}
        >
          <span>{s.name ?? '(service?)'}</span>
          {s.gross != null && (
            <span className={`tabular-nums font-normal ${priceClass}`}>{usdShort(s.gross)}</span>
          )}
        </span>
      ))}
    </div>
  );
}

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
    <div className="mt-1.5 space-y-0.5 rounded-md bg-zinc-50 px-2 py-1.5 text-xs text-zinc-600 ring-1 ring-zinc-200">
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

/** Per-row fetch state for the AI triage. */
type FetchState = { kind: 'idle' } | { kind: 'loading' } | { kind: 'error'; message: string };

export default function SuspiciousList({
  initial,
  aiTriageEnabled,
  year,
  month,
}: {
  initial: SuspiciousBooking[];
  aiTriageEnabled: boolean;
  year: number;
  month: number;
}) {
  const [items, setItems] = useState(initial);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState('');
  // Triage fetch state per booking (keyed by bookingId). Lifted out of TriagePanel so the trigger
  // can live in the action row alongside the Clear/Undo button rather than buried in the content.
  const [fetchStates, setFetchStates] = useState<Record<string, FetchState>>({});
  // Tracks which triages the owner has already given feedback on (resets per page load).
  const [feedbackSent, setFeedbackSent] = useState<Record<string, boolean>>({});

  function setFetch(bookingId: string, state: FetchState) {
    setFetchStates((prev) => ({ ...prev, [bookingId]: state }));
  }
  function rememberTriage(bookingId: string, triage: TriageResult) {
    setItems((prev) => prev.map((x) => (x.bookingId === bookingId ? { ...x, triage } : x)));
  }

  const uncleared = items.filter((i) => !i.cleared);
  const cleared   = items.filter((i) =>  i.cleared);

  async function runTriage(b: SuspiciousBooking) {
    setFetch(b.bookingId, { kind: 'loading' });
    try {
      const result = await api.requestTriage(b.bookingId, year, month);
      rememberTriage(b.bookingId, result);
      setFetch(b.bookingId, { kind: 'idle' });
    } catch (e) {
      setFetch(b.bookingId, {
        kind: 'error',
        message: e instanceof Error ? e.message : 'AI explanation unavailable.',
      });
    }
  }

  async function sendFeedback(
    b: SuspiciousBooking,
    helpful: boolean,
    corrected: TriageClassification | null,
  ) {
    try {
      await api.submitTriageFeedback(b.bookingId, helpful, corrected);
      setFeedbackSent((prev) => ({ ...prev, [b.bookingId]: true }));
    } catch {
      // Silent on feedback failures — the user already got value from the triage itself.
    }
  }

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

  /** Inline rendering of the AI triage content + loading/error states for one row. */
  function TriageContent({ b }: { b: SuspiciousBooking }) {
    if (!aiTriageEnabled) return null;
    const fs = fetchStates[b.bookingId] ?? { kind: 'idle' };
    if (b.triage) {
      return (
        <TriageResultDisplay
          bookingId={b.bookingId}
          result={b.triage}
          feedbackSent={!!feedbackSent[b.bookingId]}
          onFeedback={(helpful, corrected) => sendFeedback(b, helpful, corrected)}
        />
      );
    }
    if (fs.kind === 'loading') {
      return (
        <div
          data-testid={`triage-loading-${b.bookingId}`}
          className="mt-2 rounded-md bg-zinc-50 px-3 py-2 text-xs text-zinc-500 ring-1 ring-zinc-200"
        >
          Asking the AI to look at this booking…
        </div>
      );
    }
    if (fs.kind === 'error') {
      return (
        <div
          data-testid={`triage-error-${b.bookingId}`}
          className="mt-2 rounded-md bg-red-50 px-3 py-2 text-xs text-red-700 ring-1 ring-red-200"
        >
          <p>{fs.message}</p>
          <button onClick={() => runTriage(b)} className="mt-1 underline">
            Try again
          </button>
        </div>
      );
    }
    return null;
  }

  /** Footer action row — Explain (left) + Clear/Undo (right). Always-visible so the owner can act without scrolling past the AI content. */
  function ActionRow({
    b,
    primary,
  }: {
    b: SuspiciousBooking;
    primary: 'clear' | 'undo';
  }) {
    const fs = fetchStates[b.bookingId] ?? { kind: 'idle' };
    const showExplain = aiTriageEnabled && !b.triage && fs.kind !== 'loading';
    return (
      <div className="mt-2 flex items-center justify-between gap-2 border-t border-zinc-100 pt-2">
        <div>
          {showExplain && (
            <ExplainButton
              bookingId={b.bookingId}
              onClick={() => runTriage(b)}
              loading={false}
            />
          )}
        </div>
        {primary === 'clear' ? (
          <button
            data-testid={`suspicious-clear-${b.bookingId}`}
            onClick={() => clear(b)}
            disabled={busyId === b.bookingId}
            className="rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-800 disabled:opacity-50"
          >
            {busyId === b.bookingId ? 'Clearing…' : 'Clear'}
          </button>
        ) : (
          <button
            data-testid={`suspicious-undo-${b.bookingId}`}
            onClick={() => undo(b)}
            disabled={busyId === b.bookingId}
            className="rounded border border-zinc-300 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 disabled:opacity-50"
          >
            {busyId === b.bookingId ? 'Undoing…' : 'Undo'}
          </button>
        )}
      </div>
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
                  className="flex flex-col gap-1 px-3 py-2.5 text-sm">
                <div className="font-medium">
                  {b.date} · <span className="font-normal text-zinc-500">{b.time}</span>
                </div>
                <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-zinc-500">
                  <CustomerLink b={b} />
                  {b.gross != null && (
                    <span className="text-zinc-400">· {usd(b.gross)} total</span>
                  )}
                  {aiTriageEnabled && <TriageBadge triage={b.triage} />}
                </div>
                <ServiceChips services={b.services} fallback={b.serviceName} />
                <AppointmentNotes b={b} />
                <TriageContent b={b} />
                <ActionRow b={b} primary="clear" />
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
                  className="flex flex-col gap-1 px-3 py-2.5 text-sm">
                <div className="text-zinc-600">
                  {b.date} · <span className="text-zinc-400">{b.time}</span>
                </div>
                <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-zinc-500">
                  <CustomerLink b={b} />
                  {b.gross != null && (
                    <span className="text-zinc-400">· {usd(b.gross)} total</span>
                  )}
                  {aiTriageEnabled && <TriageBadge triage={b.triage} />}
                </div>
                <ServiceChips services={b.services} fallback={b.serviceName} muted />
                <AppointmentNotes b={b} />
                <TriageContent b={b} />
                <div className="mt-1 text-[11px] text-zinc-400">
                  cleared by {b.clearedBy ?? '?'} {fmtClearedAt(b.clearedAt) && `· ${fmtClearedAt(b.clearedAt)}`}
                </div>
                <ActionRow b={b} primary="undo" />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
