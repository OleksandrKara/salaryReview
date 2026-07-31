'use client';

/**
 * "SMS marketing consent on file" indicator, shared across the conversation list row, thread
 * header, and contact info panel so all three stay visually and semantically identical. Reflects
 * consent from *either* source — this app's own marketing.contacts capture, or the customer
 * belonging to Square's own Text Subscribers segment (see
 * MarketingContactsService#resolveDisplayNames on the backend) — which is exactly what
 * SmsConversationDto.smsConsent already resolves to, regardless of whether this phone number ever
 * went through the tracked capture flow.
 *
 * Rendered only when true, matching this page's other status indicators (unread badge, delivery-
 * failure warning) — the icon's absence means "no consent on file," not "unknown."
 */
export default function SmsConsentIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="SMS marketing consent on file (Square or salon booking form)" className="inline-flex shrink-0 text-emerald-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <circle cx="12" cy="12" r="10" />
        <path d="m9 12 2 2 4-4" />
      </svg>
      <span className="sr-only">SMS marketing consent on file</span>
    </span>
  );
}
