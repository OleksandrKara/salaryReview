'use client';

/**
 * VIP badge — shown only for a contact whose real (distinct-day) visit count has crossed the
 * configured threshold (see backend MarketingContactsService#visitCountsByCustomerId). Rendered
 * only when true, matching this app's other status indicators (SmsConsentIcon, unread badge):
 * the badge's absence means "not (yet) a VIP", not "unknown". Strictly data-driven — there's no
 * manual override, so this can never be wrong in a way a manager could accidentally cause.
 */
export default function VipBadge({ visitCount, size = 14 }: { visitCount: number | null; size?: number }) {
  const title = visitCount != null ? `VIP — ${visitCount} visits on record` : 'VIP customer';
  return (
    <span title={title} className="inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-50 px-1.5 py-0.5 text-amber-700 ring-1 ring-inset ring-amber-200">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden>
        <path d="M12 2.5 14.9 9l7.1.6-5.4 4.6 1.7 6.9L12 17.8 5.7 21.1l1.7-6.9L2 9.6 9.1 9z" />
      </svg>
      <span className="text-[10px] font-semibold uppercase tracking-wide">VIP</span>
      <span className="sr-only">{title}</span>
    </span>
  );
}
