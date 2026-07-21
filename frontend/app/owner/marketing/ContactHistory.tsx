'use client';

import { useState } from 'react';
import type { MarketingContactAppointment, MarketingContactSubmission } from '../../lib/types';

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

const CANCELLED_STATUSES = new Set(['CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_SELLER', 'DECLINED', 'NO_SHOW']);

export const PAYMENT_CHANNEL_LABELS: Record<string, string> = {
  CASH: 'Cash',
  CARD: 'Card',
  'CASH-NOTE': 'Cash (noted)',
};

export function PaymentChannelBadge({ channel }: { channel: string }) {
  const cls = channel === 'CASH-NOTE' ? 'bg-amber-50 text-amber-700 ring-amber-200' : 'bg-blue-50 text-blue-700 ring-blue-200';
  return (
    <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {PAYMENT_CHANNEL_LABELS[channel] ?? channel}
    </span>
  );
}

function AppointmentStatusBadge({ status }: { status: string }) {
  const cls =
    status === 'ACCEPTED'
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
      : CANCELLED_STATUSES.has(status)
        ? 'bg-red-50 text-red-700 ring-red-200'
        : 'bg-zinc-100 text-zinc-600 ring-zinc-200';
  return (
    <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {APPOINTMENT_STATUS_LABELS[status] ?? status}
    </span>
  );
}

/** Empty state is visible without a click (a plain label); non-empty is a toggle showing the
 * count, so an owner never has to expand something just to learn it's empty. Shared by
 * ContactsTable (per contact) and the Ads Report breakdown drill-down (per Square customer id,
 * fetched lazily — see AdsReportView's CompletedList/UpcomingList).
 */
export function HistoryToggle({ label, count, open, onClick }: { label: string; count: number; open: boolean; onClick: () => void }) {
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

export function AppointmentHistoryList({ appointments }: { appointments: MarketingContactAppointment[] }) {
  // Captured once at mount via useState's lazy initializer (React's documented escape hatch for
  // an impure read like Date.now()) rather than called directly during render, which the
  // react-hooks/purity rule flags even inside a useMemo factory.
  const [now] = useState(() => Date.now());
  return (
    <ul className="flex flex-col gap-2">
      {appointments.map((a) => {
        const upcoming = a.startAt != null && new Date(a.startAt).getTime() > now;
        const hasSubmission = a.submissionOccurredAt != null;
        const cancelled = CANCELLED_STATUSES.has(a.status);
        const noPaymentFound = !upcoming && !cancelled && a.paymentChannel == null;
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
                {a.paymentChannel != null && a.collectedAmount != null
                  ? ` · ${usd(a.collectedAmount)} collected`
                  : a.price != null
                    ? ` · ~${usd(a.price)}`
                    : ''}
              </div>
              {noPaymentFound && (
                <div className="mt-1 text-xs font-medium text-amber-600">No payment on file</div>
              )}
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
            <div className="flex flex-col items-end gap-1">
              <AppointmentStatusBadge status={a.status} />
              {a.paymentChannel != null && <PaymentChannelBadge channel={a.paymentChannel} />}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

export function SubmissionHistoryList({ submissions }: { submissions: MarketingContactSubmission[] }) {
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
