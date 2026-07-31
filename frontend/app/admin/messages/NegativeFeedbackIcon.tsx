'use client';

/**
 * "Has ever left negative feedback" indicator — shared across the conversation list row, thread
 * header, and contact info panel, mirroring {@link SmsConsentIcon}. Set when a phone number has
 * ever replied to the checkout-review-request automation with a low (1-4) star rating; permanent
 * once true, even once the conversation moves on to friendlier messages — matching how
 * SameDayRebookingScheduler permanently excludes that phone number from the win-back nudge.
 *
 * Amber (not red) — this is a "handle with care" signal for a manager, not a system failure like
 * the delivery-status warning, so it's kept visually distinct from that one.
 */
export default function NegativeFeedbackIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="Has left negative feedback before" className="inline-flex shrink-0 text-amber-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M17 14V2" />
        <path d="M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.8 1.1l-.7 1.4a2 2 0 0 1-1.79 1.11L9 18.12Z" />
      </svg>
      <span className="sr-only">Has left negative feedback before</span>
    </span>
  );
}
