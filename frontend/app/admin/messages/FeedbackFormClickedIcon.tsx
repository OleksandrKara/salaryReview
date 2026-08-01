'use client';

/**
 * "Has clicked the feedback-form link before" indicator — shared across the conversation list row
 * and thread header, mirroring {@link SmsConsentIcon}/{@link VipIcon}'s own convention (bare icon,
 * title tooltip, sr-only text, rendered only when true). Quick-glance version of the fuller
 * sent/clicked/date detail already in the contact info panel's "Review links" section.
 *
 * A clipboard-check shape in teal — deliberately distinct from
 * {@link GoogleReviewClickedIcon}'s star (a different link/destination) and from
 * {@link SmsConsentIcon}'s circular check (a different fact entirely).
 */
export default function FeedbackFormClickedIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="Has clicked the feedback form link before" className="inline-flex shrink-0 text-teal-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <rect width="14" height="18" x="5" y="3" rx="2" />
        <path d="M9 3a1 1 0 0 0-1 1v1a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1" />
        <path d="m9 13 2 2 4-4" />
      </svg>
      <span className="sr-only">Has clicked the feedback form link before</span>
    </span>
  );
}
