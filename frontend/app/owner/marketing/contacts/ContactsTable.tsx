'use client';

import { Fragment, useState } from 'react';
import { api } from '../../../lib/api';
import type { MarketingContact, MarketingContactAppointment, MarketingContactHistory, MarketingContactSubmission } from '../../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const fmtDate = (iso: string) =>
  new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

const fmtDateShort = (iso: string) => new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });

const SUBMISSION_LABELS: Record<string, string> = {
  step1: 'Lead capture',
  booking: 'Booking',
  four_hand_request: '4-hand request',
};

function ConsentBadge({ label, value }: { label: string; value: boolean | null }) {
  const text = value === null ? 'Unknown' : value ? 'Yes' : 'No';
  const cls =
    value === true
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
      : value === false
        ? 'bg-zinc-100 text-zinc-500 ring-zinc-200'
        : 'bg-zinc-50 text-zinc-400 ring-zinc-200';
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {label}: {text}
    </span>
  );
}

function AppointmentInfo({ c }: { c: MarketingContact }) {
  if (!c.hasAppointment) {
    return <span className="text-xs text-zinc-400">No appointment yet</span>;
  }
  return (
    <div className="text-sm">
      <div className="font-medium">
        {c.bookingStartAt ? fmtDate(c.bookingStartAt) : '—'}
        {c.bookingArtistName ? ` · ${c.bookingArtistName}` : ''}
      </div>
      <div className="text-xs text-zinc-500">
        {c.bookingServiceName ?? '—'}
        {c.bookingPrice != null ? ` · ${usd(c.bookingPrice)}` : ''}
        {c.bookingStatus ? ` · ${c.bookingStatus}` : ''}
      </div>
      {c.squareProfileUrl && (
        <a href={c.squareProfileUrl} target="_blank" rel="noopener noreferrer" className="text-xs font-medium text-blue-600 hover:underline">
          View in Square →
        </a>
      )}
    </div>
  );
}

function SourceInfo({ c }: { c: MarketingContact }) {
  const same = c.originalTrafficSource === c.marketingTrafficSource;
  return (
    <div className="text-xs">
      <div>
        <span className="text-zinc-500">First:</span> {c.originalTrafficSource ?? '—'}
      </div>
      {!same && (
        <div>
          <span className="text-zinc-500">Latest:</span> {c.marketingTrafficSource ?? '—'}
        </div>
      )}
      {(c.landingPageSlug || c.variantName) && (
        <div className="mt-1 text-zinc-400">
          {c.landingPageSlug ?? '—'}
          {c.variantName ? ` · ${c.variantName}` : ''}
        </div>
      )}
    </div>
  );
}

function DeviceInfo({ c }: { c: MarketingContact }) {
  if (!c.deviceType && !c.osName && !c.browserName) {
    return <span className="text-xs text-zinc-400">—</span>;
  }
  return (
    <div className="text-xs text-zinc-600">
      {c.deviceType && <div className="capitalize">{c.deviceType}</div>}
      <div className="text-zinc-400">
        {[c.osName, c.osVersion].filter(Boolean).join(' ')}
        {c.browserName ? ` · ${c.browserName}` : ''}
      </div>
    </div>
  );
}

function HistoryToggle({ open, onClick }: { open: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium text-blue-600 ring-1 ring-blue-200 hover:bg-blue-50"
    >
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"
        className={`transition-transform ${open ? 'rotate-90' : ''}`} aria-hidden>
        <polyline points="9 6 15 12 9 18" />
      </svg>
      History
    </button>
  );
}

function AppointmentStatusBadge({ status }: { status: string }) {
  const cls =
    status === 'ACCEPTED'
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
      : status === 'CANCELLED_BY_CUSTOMER' || status === 'CANCELLED_BY_SELLER' || status === 'DECLINED'
        ? 'bg-red-50 text-red-700 ring-red-200'
        : 'bg-zinc-100 text-zinc-600 ring-zinc-200';
  return <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>{status}</span>;
}

function AppointmentHistoryList({ appointments }: { appointments: MarketingContactAppointment[] }) {
  const now = Date.now();
  return (
    <ul className="flex flex-col gap-2">
      {appointments.map((a) => {
        const upcoming = a.startAt != null && new Date(a.startAt).getTime() > now;
        return (
          <li key={a.bookingId} className="flex items-start justify-between gap-3 rounded-md bg-white p-2 ring-1 ring-zinc-100">
            <div>
              <div className="text-sm font-medium">
                {a.startAt ? fmtDate(a.startAt) : 'Date unknown'}
                {upcoming && <span className="ml-1.5 text-xs font-medium text-blue-600">Upcoming</span>}
              </div>
              <div className="text-xs text-zinc-500">
                {a.serviceName ?? 'Service unknown'}
                {a.artistName ? ` · ${a.artistName}` : ''}
                {a.price != null ? ` · ~${usd(a.price)}` : ''}
              </div>
            </div>
            <AppointmentStatusBadge status={a.status} />
          </li>
        );
      })}
    </ul>
  );
}

function SubmissionHistoryList({ submissions }: { submissions: MarketingContactSubmission[] }) {
  return (
    <ul className="flex flex-col gap-2">
      {submissions.map((s, i) => (
        <li key={i} className="flex items-start justify-between gap-3 rounded-md bg-white p-2 ring-1 ring-zinc-100">
          <div>
            <div className="text-sm font-medium">{fmtDateShort(s.occurredAt)}</div>
            <div className="text-xs text-zinc-500">
              {s.landingPageSlug ?? '—'}
              {s.variantName ? ` · ${s.variantName}` : ''}
            </div>
            {(s.utmSource || s.utmMedium || s.utmCampaign) && (
              <div className="text-xs text-zinc-400">
                {[s.utmSource, s.utmMedium, s.utmCampaign].filter(Boolean).join(' / ')}
              </div>
            )}
          </div>
          <span className="whitespace-nowrap rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 ring-1 ring-inset ring-zinc-200">
            {SUBMISSION_LABELS[s.submissionType] ?? s.submissionType}
          </span>
        </li>
      ))}
    </ul>
  );
}

type HistoryState = { status: 'loading' } | { status: 'error' } | { status: 'ready'; data: MarketingContactHistory };

function HistoryPanel({ c, state }: { c: MarketingContact; state: HistoryState | undefined }) {
  if (!state || state.status === 'loading') {
    return <p className="text-xs text-zinc-400">Loading history…</p>;
  }
  if (state.status === 'error') {
    return <p className="text-xs text-red-600">Couldn&apos;t load history — try again.</p>;
  }

  const { submissions, appointments } = state.data;
  return (
    <div className="flex flex-col gap-4">
      {c.squareProfileUrl && (
        <div>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">Square Appointment History</h4>
          {appointments.length === 0 ? (
            <p className="text-xs text-zinc-400">No Square appointments found for this customer.</p>
          ) : (
            <AppointmentHistoryList appointments={appointments} />
          )}
        </div>
      )}
      <div>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">Submission History</h4>
        <SubmissionHistoryList submissions={submissions} />
      </div>
    </div>
  );
}

export default function ContactsTable({ contacts }: { contacts: MarketingContact[] }) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [history, setHistory] = useState<Record<string, HistoryState>>({});

  function toggle(c: MarketingContact) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(c.id)) {
        next.delete(c.id);
      } else {
        next.add(c.id);
        if (!history[c.id]) {
          setHistory((h) => ({ ...h, [c.id]: { status: 'loading' } }));
          api
            .getMarketingContactHistory(c.id)
            .then((data) => setHistory((h) => ({ ...h, [c.id]: { status: 'ready', data } })))
            .catch(() => setHistory((h) => ({ ...h, [c.id]: { status: 'error' } })));
        }
      }
      return next;
    });
  }

  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {contacts.map((c) => (
          <div key={c.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{c.givenName ?? '—'}</span>
              <span className={`text-xs font-medium ${c.hasAppointment ? 'text-emerald-700' : 'text-zinc-400'}`}>
                {c.hasAppointment ? 'Booked' : 'Lead only'}
              </span>
            </div>
            <div className="mt-1 text-sm text-zinc-600">{c.phoneNumber}</div>
            {c.emailAddress && <div className="text-sm text-zinc-600">{c.emailAddress}</div>}
            <div className="mt-2">
              <SourceInfo c={c} />
            </div>
            <div className="mt-2">
              <DeviceInfo c={c} />
            </div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              <ConsentBadge label="SMS" value={c.smsMarketingConsent} />
              <ConsentBadge label="Email" value={c.emailMarketingConsent} />
            </div>
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <AppointmentInfo c={c} />
            </div>
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <HistoryToggle open={expanded.has(c.id)} onClick={() => toggle(c)} />
              {expanded.has(c.id) && (
                <div className="mt-3 rounded-md bg-zinc-50 p-3">
                  <HistoryPanel c={c} state={history[c.id]} />
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      {/* Desktop table */}
      <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Contact</th>
              <th className="px-3 py-2">Source</th>
              <th className="px-3 py-2">Device</th>
              <th className="px-3 py-2">Consent</th>
              <th className="px-3 py-2">Appointment</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {contacts.map((c) => (
              <Fragment key={c.id}>
                <tr className="hover:bg-zinc-50">
                  <td className="px-3 py-2">
                    <div className="font-medium">{c.givenName ?? '—'}</div>
                    <div className="text-xs text-zinc-500">{c.phoneNumber}</div>
                    {c.emailAddress && <div className="text-xs text-zinc-500">{c.emailAddress}</div>}
                  </td>
                  <td className="px-3 py-2">
                    <SourceInfo c={c} />
                  </td>
                  <td className="px-3 py-2">
                    <DeviceInfo c={c} />
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex flex-col gap-1">
                      <ConsentBadge label="SMS" value={c.smsMarketingConsent} />
                      <ConsentBadge label="Email" value={c.emailMarketingConsent} />
                    </div>
                  </td>
                  <td className="px-3 py-2">
                    <AppointmentInfo c={c} />
                  </td>
                  <td className="px-3 py-2">
                    <HistoryToggle open={expanded.has(c.id)} onClick={() => toggle(c)} />
                  </td>
                </tr>
                {expanded.has(c.id) && (
                  <tr className="bg-zinc-50">
                    <td colSpan={6} className="px-3 py-3">
                      <HistoryPanel c={c} state={history[c.id]} />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
