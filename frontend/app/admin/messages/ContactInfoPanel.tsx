'use client';

import type { MarketingContact, MarketingContactAppointment } from '../../lib/types';
import SmsConsentIcon from './SmsConsentIcon';
import NegativeFeedbackIcon from './NegativeFeedbackIcon';
import VipIcon from './VipIcon';
import BlockedIcon from './BlockedIcon';
import GoogleReviewClickedIcon from './GoogleReviewClickedIcon';
import YelpReviewClickedIcon from './YelpReviewClickedIcon';
import FeedbackFormClickedIcon from './FeedbackFormClickedIcon';
import SpamFlagIcon from './SpamFlagIcon';

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

/** "Never sent" rows are filtered out by the caller before this runs — every row shown here was
 * at least attempted, so the only two states worth distinguishing are "clicked" (with when) vs.
 * "sent but hasn't been opened yet". */
function linkEngagementBadge(clickedAt: string | null): { label: string; className: string } {
  if (clickedAt) {
    return { label: `Opened ${formatDate(clickedAt)}`, className: 'bg-emerald-50 text-emerald-700' };
  }
  return { label: 'Not opened yet', className: 'bg-amber-50 text-amber-700' };
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
  smsConsent,
  hasNegativeFeedback,
  vip,
  visitCount,
  blocked,
  optedOut,
  clickedGoogleReview,
  clickedYelpReview,
  clickedFeedbackForm,
  flaggedAsSpam,
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
  /** From the conversation list's own resolution — true if consent comes from *either* source
   * (this app's own marketing.contacts capture, or Square's Text Subscribers segment). Preferred
   * over `contact?.smsMarketingConsent`, which is always false/undefined for a phone number with
   * no marketing.contacts row at all, even when it does have Square-only consent. */
  smsConsent?: boolean;
  /** True if this phone number has *ever* left a low (1-4) star rating on the checkout-review-
   * request automation — permanent once true. Same phone number is permanently excluded from the
   * same-day-rebooking win-back nudge on the backend, so a manager knows why before reaching out. */
  hasNegativeFeedback?: boolean;
  /** From the conversation list's own resolution — same distinct-day visit-count threshold as
   * MarketingContact#vip, preferred over `contact?.vip` for the same reason as smsConsent above
   * (resolves even without a marketing.contacts row). */
  vip?: boolean;
  visitCount?: number | null;
  /** True if a manager has blocked this number, or the customer texted a STOP-style opt-out
   * keyword — see BlockedIcon's own doc comment. */
  blocked?: boolean;
  /** True if `blocked` is true because the customer opted out via STOP, not a manual block —
   * see BlockedIcon's own doc comment. */
  optedOut?: boolean;
  /** True if this phone number has *ever* clicked the checkout-review-request automation's
   * Google review link — see GoogleReviewClickedIcon's own doc comment. Distinct from the fuller
   * sent/clicked/date detail in the "Review links" section below, which only appears once
   * `contact` resolves to a real marketing.contacts row; this flag resolves independently of
   * that, so it can still show here even when that section is hidden. */
  clickedGoogleReview?: boolean;
  /** Same as clickedGoogleReview, for the checkout-review automation's Yelp-review escalation
   * rung — see YelpReviewClickedIcon. */
  clickedYelpReview?: boolean;
  /** Same as clickedGoogleReview, for the feedback-form link — see FeedbackFormClickedIcon. */
  clickedFeedbackForm?: boolean;
  /** True if any outbound message to this number has ever come back flagged as spam by the
   * carrier or opted-out (replied STOP) — see SpamFlagIcon's own doc comment. */
  flaggedAsSpam?: boolean;
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
              <div className="flex flex-wrap items-center gap-1.5">
                <div data-testid="contact-info-name" className="min-w-0 flex-1 truncate text-base font-semibold text-zinc-900">
                  {name ?? formatPhone(phoneNumber)}
                </div>
                {vip ? (
                  <span
                    data-testid="contact-info-vip-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700"
                    title={visitCount != null ? `VIP — ${visitCount} visits on record` : 'VIP customer'}
                  >
                    <VipIcon size={11} />
                    VIP{visitCount != null ? ` · ${visitCount} visits` : ''}
                  </span>
                ) : null}
                {smsConsent ? (
                  <span
                    data-testid="contact-info-consent-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700"
                    title="Consent on file via Square's Text Subscribers segment or the salon's own booking form"
                  >
                    <SmsConsentIcon size={11} />
                    SMS OK
                  </span>
                ) : null}
                {hasNegativeFeedback ? (
                  <span
                    data-testid="contact-info-negative-feedback-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700"
                    title="Has left negative feedback before"
                  >
                    <NegativeFeedbackIcon size={11} />
                    Negative feedback
                  </span>
                ) : null}
                {blocked ? (
                  <span
                    data-testid="contact-info-blocked-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-medium text-red-700"
                    title={optedOut
                      ? 'Customer replied STOP — TwilioSmsService refuses to send it any further SMS'
                      : 'Number blocked — TwilioSmsService refuses to send it any further SMS'}
                  >
                    <BlockedIcon optedOut={optedOut} size={11} />
                    {optedOut ? 'Opted out' : 'Blocked'}
                  </span>
                ) : null}
                {flaggedAsSpam ? (
                  <span
                    data-testid="contact-info-spam-flag-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-medium text-red-700"
                    title="Flagged as spam by carrier, or recipient opted out — see the thread for the specific message"
                  >
                    <SpamFlagIcon size={11} />
                    Spam/opt-out
                  </span>
                ) : null}
                {clickedGoogleReview ? (
                  <span
                    data-testid="contact-info-google-review-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-medium text-blue-700"
                    title="Has clicked the Google review link before"
                  >
                    <GoogleReviewClickedIcon size={11} />
                    Clicked Google review
                  </span>
                ) : null}
                {clickedYelpReview ? (
                  <span
                    data-testid="contact-info-yelp-review-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-rose-50 px-2 py-0.5 text-[10px] font-medium text-rose-700"
                    title="Has clicked the Yelp review link before"
                  >
                    <YelpReviewClickedIcon size={11} />
                    Clicked Yelp review
                  </span>
                ) : null}
                {clickedFeedbackForm ? (
                  <span
                    data-testid="contact-info-feedback-form-badge"
                    className="flex shrink-0 items-center gap-1 rounded-full bg-teal-50 px-2 py-0.5 text-[10px] font-medium text-teal-700"
                    title="Has clicked the feedback form link before"
                  >
                    <FeedbackFormClickedIcon size={11} />
                    Clicked feedback form
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

            {/* Whether this contact has ever been sent — and actually clicked — the checkout-
                review automation's Google-review / Yelp-review / feedback-form links. A row only
                appears once that link type has actually been sent at least once (sentAt non-null);
                "never sent" isn't shown as a row at all, since that's the common case for most
                contacts and would otherwise just be noise. */}
            {contact && (contact.googleReviewSentAt || contact.yelpReviewSentAt || contact.feedbackFormSentAt) ? (
              <div className="mb-5">
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-400">Review links</h3>
                <ul className="flex flex-col gap-2">
                  {contact.googleReviewSentAt ? (
                    <li
                      data-testid="contact-info-review-link"
                      data-link-target="GOOGLE_REVIEW"
                      className="flex items-center justify-between gap-2 rounded-lg ring-1 ring-zinc-100 px-3 py-2 text-sm"
                    >
                      <span className="flex items-center gap-1.5 text-zinc-700">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-zinc-400">
                          <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                          <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
                        </svg>
                        Google review
                      </span>
                      {(() => {
                        const badge = linkEngagementBadge(contact.googleReviewClickedAt);
                        return (
                          <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                            {badge.label}
                          </span>
                        );
                      })()}
                    </li>
                  ) : null}
                  {contact.yelpReviewSentAt ? (
                    <li
                      data-testid="contact-info-review-link"
                      data-link-target="YELP_REVIEW"
                      className="flex items-center justify-between gap-2 rounded-lg ring-1 ring-zinc-100 px-3 py-2 text-sm"
                    >
                      <span className="flex items-center gap-1.5 text-zinc-700">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-zinc-400">
                          <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                          <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
                        </svg>
                        Yelp review
                      </span>
                      {(() => {
                        const badge = linkEngagementBadge(contact.yelpReviewClickedAt);
                        return (
                          <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                            {badge.label}
                          </span>
                        );
                      })()}
                    </li>
                  ) : null}
                  {contact.feedbackFormSentAt ? (
                    <li
                      data-testid="contact-info-review-link"
                      data-link-target="FEEDBACK_FORM"
                      className="flex items-center justify-between gap-2 rounded-lg ring-1 ring-zinc-100 px-3 py-2 text-sm"
                    >
                      <span className="flex items-center gap-1.5 text-zinc-700">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-zinc-400">
                          <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                          <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
                        </svg>
                        Feedback form
                      </span>
                      {(() => {
                        const badge = linkEngagementBadge(contact.feedbackFormClickedAt);
                        return (
                          <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${badge.className}`}>
                            {badge.label}
                          </span>
                        );
                      })()}
                    </li>
                  ) : null}
                </ul>
              </div>
            ) : null}

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
