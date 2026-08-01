'use client';

/**
 * "VIP customer" indicator, shared across the conversation list row, thread header, and contact
 * info panel — mirrors {@link SmsConsentIcon}/{@link NegativeFeedbackIcon}'s own convention (bare
 * icon, title tooltip, rendered only when true). Set once this Square customer's distinct-day
 * visit count reaches the configured threshold (see backend
 * MarketingContactsService#visitCountsByCustomerId) — strictly data-driven, no manual override.
 *
 * Amber (matching the Contacts tab's own VipBadge) — distinct from the emerald consent check and
 * the amber negative-feedback flag reads as "handle with care" too, but the star shape keeps the
 * two from being confused at a glance.
 */
export default function VipIcon({ visitCount, size = 14 }: { visitCount?: number | null; size?: number }) {
  const title = visitCount != null ? `VIP — ${visitCount} visits on record` : 'VIP customer';
  return (
    <span title={title} className="inline-flex shrink-0 text-amber-500">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden>
        <path d="M12 2.5 14.9 9l7.1.6-5.4 4.6 1.7 6.9L12 17.8 5.7 21.1l1.7-6.9L2 9.6 9.1 9z" />
      </svg>
      <span className="sr-only">{title}</span>
    </span>
  );
}
