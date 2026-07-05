'use client';

import { Fragment, useState } from 'react';
import type { MarketingContact, MarketingContactAppointment, MarketingContactSubmission } from '../../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const fmtDate = (iso: string) =>
  new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

const SUBMISSION_LABELS: Record<string, string> = {
  step1: 'Lead capture',
  booking: 'Booking',
  four_hand_request: '4-hand request',
};

const APPOINTMENT_STATUS_LABELS: Record<string, string> = {
  ACCEPTED: 'Confirmed',
  PENDING: 'Pending',
  CANCELLED_BY_CUSTOMER: 'Cancelled by customer',
  CANCELLED_BY_SELLER: 'Cancelled by salon',
  DECLINED: 'Declined',
  NO_SHOW: 'No-show',
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

/** Empty state is visible without a click (a plain label); non-empty is a toggle showing the
 * count, so an owner never has to expand something just to learn it's empty.
 */
function HistoryToggle({ label, count, open, onClick }: { label: string; count: number; open: boolean; onClick: () => void }) {
  if (count === 0) {
    return <span className="text-xs text-zinc-400">No {label.toLowerCase()}</span>;
  }
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
      {label} ({count})
    </button>
  );
}

function AppointmentStatusBadge({ status }: { status: string }) {
  const cls =
    status === 'ACCEPTED'
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
      : status === 'CANCELLED_BY_CUSTOMER' || status === 'CANCELLED_BY_SELLER' || status === 'DECLINED' || status === 'NO_SHOW'
        ? 'bg-red-50 text-red-700 ring-red-200'
        : 'bg-zinc-100 text-zinc-600 ring-zinc-200';
  return (
    <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {APPOINTMENT_STATUS_LABELS[status] ?? status}
    </span>
  );
}

function AppointmentHistoryList({ appointments }: { appointments: MarketingContactAppointment[] }) {
  const now = Date.now();
  return (
    <ul className="flex flex-col gap-2">
      {appointments.map((a) => {
        const upcoming = a.startAt != null && new Date(a.startAt).getTime() > now;
        const hasSubmission = a.submissionOccurredAt != null;
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
              {hasSubmission ? (
                <div className="mt-1 text-xs text-zinc-400">
                  Booked {fmtDate(a.submissionOccurredAt as string)} · {a.trafficSource ?? '—'}
                  {(a.deviceType || a.osName || a.browserName) && (
                    <>
                      {' '}
                      · {a.deviceType ?? ''} {[a.osName, a.osVersion].filter(Boolean).join(' ')}
                      {a.browserName ? ` · ${a.browserName}` : ''}
                    </>
                  )}
                </div>
              ) : (
                <div className="mt-1 text-xs text-zinc-300">Not booked through this landing page</div>
              )}
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
            <div className="text-sm font-medium">{fmtDate(s.occurredAt)}</div>
            <div className="text-xs text-zinc-500">
              {s.landingPageSlug ?? '—'}
              {s.variantName ? ` · ${s.variantName}` : ''}
            </div>
            <div className="text-xs text-zinc-400">{s.trafficSource ?? '—'}</div>
          </div>
          <span className="whitespace-nowrap rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 ring-1 ring-inset ring-zinc-200">
            {SUBMISSION_LABELS[s.submissionType] ?? s.submissionType}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Only rendered when a Square customer is actually known for this contact. */
function SquareProfileLink({ url }: { url: string }) {
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="whitespace-nowrap text-xs font-medium text-blue-600 hover:underline"
    >
      View in Square →
    </a>
  );
}

function ExpandedSections({ c, showAppointments, showSubmissions }: { c: MarketingContact; showAppointments: boolean; showSubmissions: boolean }) {
  if (!showAppointments && !showSubmissions) return null;
  return (
    <div className="flex flex-col gap-4">
      {showAppointments && (
        <div>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">Appointment History</h4>
          <AppointmentHistoryList appointments={c.appointments} />
        </div>
      )}
      {showSubmissions && (
        <div>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">Submission History</h4>
          <SubmissionHistoryList submissions={c.submissions} />
        </div>
      )}
    </div>
  );
}

export default function ContactsTable({ contacts }: { contacts: MarketingContact[] }) {
  const [expandedAppointments, setExpandedAppointments] = useState<Set<string>>(new Set());
  const [expandedSubmissions, setExpandedSubmissions] = useState<Set<string>>(new Set());

  function toggle(set: Set<string>, setSet: (s: Set<string>) => void, id: string) {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSet(next);
  }

  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {contacts.map((c) => (
          <div key={c.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{c.givenName ?? '—'}</span>
              {c.squareProfileUrl && <SquareProfileLink url={c.squareProfileUrl} />}
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
            <div className="mt-3 flex flex-wrap items-center gap-3 border-t border-zinc-100 pt-3">
              <HistoryToggle
                label="Appointments"
                count={c.appointments.length}
                open={expandedAppointments.has(c.id)}
                onClick={() => toggle(expandedAppointments, setExpandedAppointments, c.id)}
              />
              <HistoryToggle
                label="Submissions"
                count={c.submissions.length}
                open={expandedSubmissions.has(c.id)}
                onClick={() => toggle(expandedSubmissions, setExpandedSubmissions, c.id)}
              />
            </div>
            <div className="mt-3">
              <ExpandedSections c={c} showAppointments={expandedAppointments.has(c.id)} showSubmissions={expandedSubmissions.has(c.id)} />
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
              <th className="px-3 py-2">Appointments</th>
              <th className="px-3 py-2">Submissions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {contacts.map((c) => {
              const showAppointments = expandedAppointments.has(c.id);
              const showSubmissions = expandedSubmissions.has(c.id);
              return (
                <Fragment key={c.id}>
                  <tr className="hover:bg-zinc-50">
                    <td className="px-3 py-2">
                      <div className="flex items-start justify-between gap-2">
                        <div className="font-medium">{c.givenName ?? '—'}</div>
                        {c.squareProfileUrl && <SquareProfileLink url={c.squareProfileUrl} />}
                      </div>
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
                      <HistoryToggle
                        label="Appointments"
                        count={c.appointments.length}
                        open={showAppointments}
                        onClick={() => toggle(expandedAppointments, setExpandedAppointments, c.id)}
                      />
                    </td>
                    <td className="px-3 py-2">
                      <HistoryToggle
                        label="Submissions"
                        count={c.submissions.length}
                        open={showSubmissions}
                        onClick={() => toggle(expandedSubmissions, setExpandedSubmissions, c.id)}
                      />
                    </td>
                  </tr>
                  {(showAppointments || showSubmissions) && (
                    <tr className="bg-zinc-50">
                      <td colSpan={6} className="px-3 py-3">
                        <ExpandedSections c={c} showAppointments={showAppointments} showSubmissions={showSubmissions} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
