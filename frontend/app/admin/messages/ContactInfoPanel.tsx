'use client';

import type { MarketingContact, MarketingContactAppointment } from '../../lib/types';

function formatPhone(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('1')) {
    return `(${digits.slice(1, 4)}) ${digits.slice(4, 7)}-${digits.slice(7)}`;
  }
  if (digits.length === 10) {
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  return phone;
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function formatMoney(n: number | null): string | null {
  return n == null ? null : `$${Math.round(n)}`;
}

function displayName(givenName: string | null | undefined, familyName: string | null | undefined): string | null {
  const parts = [givenName, familyName].filter((p): p is string => Boolean(p && p.trim()));
  return parts.length > 0 ? parts.join(' ') : null;
}

function appointmentBadge(a: MarketingContactAppointment): { label: string; className: string } {
  if (a.status === 'CANCELLED_BY_CUSTOMER' || a.status === 'CANCELLED_BY_SELLER' || a.status === 'DECLINED') {
    return { label: 'Cancelled', className: 'bg-zinc-100 text-zinc-500' };
  }
  if (a.status === 'NO_SHOW') {
    return { label: 'No-show', className: 'bg-amber-50 text-amber-700' };
  }
  const isFuture = a.startAt ? new Date(a.startAt).getTime() > Date.now() : false;
  return isFuture
    ? { label: 'Upcoming', className: 'bg-emerald-50 text-emerald-700' }
    : { label: 'Completed', className: 'bg-sky-50 text-sky-700' };
}

/**
 * Contact info sidebar for the conversation view — name/email plus prior Square appointments and
 * marketing.contacts form submissions, so a manager replying has the same context they'd get from
 * the Contacts tab without leaving the chat. `contact === undefined` is "still loading",
 * `contact === null` is "resolved — this number never went through the tracked capture flow",
 * both rendered distinctly rather than collapsed into one "no data" state.
 */
export default function ContactInfoPanel({
  phoneNumber,
  contact,
  squareProfileUrl,
  conversationName,
  onClose,
}: {
  phoneNumber: string;
  contact: MarketingContact | null | undefined;
  /** From the conversation list's own resolution (MarketingContactsService#resolveDisplayNames),
   * which can resolve a Square customer — and so a profile link — via a live phone lookup even
   * when `contact` is null (no marketing.contacts row at all). Preferred over `contact`'s own
   * squareProfileUrl, which requires that row to exist. */
  squareProfileUrl?: string | null;
  /** Same reasoning as squareProfileUrl — the conversation list can resolve a name for a phone
   * number that has no marketing.contacts row at all, so it's preferred over `contact`'s own
   * given/family name here too. */
  conversationName?: string | null;
  onClose: () => void;
}) {
  const resolvedSquareProfileUrl = squareProfileUrl ?? contact?.squareProfileUrl ?? null;
  const name = conversationName ?? displayName(contact?.givenName, contact?.familyName);

  return (
    <div data-testid="contact-info-panel" className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b border-zinc-200 px-4 py-3">
        <button
          type="button"
          data-testid="contact-info-panel-close-button"
          onClick={onClose}
          aria-label="Close contact info"
          className="-ml-2 flex h-11 w-11 shrink-0 items-center justify-center text-zinc-400 hover:text-zinc-600 sm:hidden"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="m15 18-6-6 6-6" />
          </svg>
        </button>
        <span className="font-medium text-zinc-900">Contact info</span>
      </div>

      <div data-testid="contact-info-panel-body" className="flex-1 overflow-y-auto overflow-x-hidden px-4 py-4">
        {contact === undefined ? (
          <p className="text-sm text-zinc-400">Loading…</p>
        ) : (
          <>
            <div className="mb-5">
              <div className="flex items-center gap-1.5">
                <div data-testid="contact-info-name" className="truncate text-base font-semibold text-zinc-900">
                  {name ?? formatPhone(phoneNumber)}
                </div>
                {contact?.smsMarketingConsent ? (
                  <span
                    className="shrink-0 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700"
                    title="Consent on file via marketing.contacts or Square's own Text Subscribers segment"
                  >
                    SMS OK
                  </span>
                ) : null}
              </div>
              <div className="mt-1 text-sm tabular-nums text-zinc-500">{formatPhone(phoneNumber)}</div>
              {contact?.emailAddress ? (
                <div className="mt-0.5 truncate text-sm text-zinc-500">{contact.emailAddress}</div>
              ) : null}
              {resolvedSquareProfileUrl ? (
                <a
                  href={resolvedSquareProfileUrl}
                  target="_blank"
                  rel="noreferrer"
                  data-testid="contact-info-square-link"
                  className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-sky-700 hover:underline"
                >
                  View in Square
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M7 17 17 7" /><path d="M7 7h10v10" />
                  </svg>
                </a>
              ) : null}
              {contact === null && !name && !resolvedSquareProfileUrl ? (
                <p className="mt-3 text-xs text-zinc-400">No profile on file for this number yet.</p>
              ) : null}
            </div>

            {contact && contact.appointments.length > 0 ? (
              <div className="mb-5">
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-400">Appointments</h3>
                <ul className="flex flex-col gap-2">
                  {contact.appointments.map((a) => {
                    const badge = appointmentBadge(a);
                    const price = formatMoney(a.collectedAmount ?? a.price);
                    return (
                      <li key={a.bookingId} data-testid="contact-info-appointment" className="rounded-lg ring-1 ring-zinc-100 px-3 py-2 text-sm">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium text-zinc-900">{formatDate(a.startAt)}</span>
                          <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                            {badge.label}
                          </span>
                        </div>
                        <div className="mt-0.5 truncate text-zinc-500">
                          {a.serviceName ?? 'Service'}
                          {a.artistName ? ` · ${a.artistName}` : ''}
                          {price ? ` · ${price}` : ''}
                        </div>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ) : null}

            {contact && contact.submissions.length > 0 ? (
              <div>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-400">Form history</h3>
                <ul className="flex flex-col gap-2">
                  {contact.submissions.map((s, i) => (
                    <li key={i} data-testid="contact-info-submission" className="rounded-lg ring-1 ring-zinc-100 px-3 py-2 text-sm">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium capitalize text-zinc-900">
                          {s.submissionType.replace(/_/g, ' ')}
                        </span>
                        <span className="shrink-0 text-xs tabular-nums text-zinc-400">{formatDate(s.occurredAt)}</span>
                      </div>
                      {s.serviceName ? <div className="mt-0.5 truncate text-zinc-500">{s.serviceName}</div> : null}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}
