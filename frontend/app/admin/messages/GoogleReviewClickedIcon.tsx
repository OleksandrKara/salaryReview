'use client';

/**
 * "Has clicked the Google review link before" indicator — shared across the conversation list
 * row and thread header, mirroring {@link SmsConsentIcon}/{@link VipIcon}'s own convention (bare
 * icon, title tooltip, sr-only text, rendered only when true). Quick-glance version of the fuller
 * sent/clicked/date detail already in the contact info panel's "Review links" section — this is
 * just so a manager scanning the list doesn't have to open that panel to see it.
 *
 * A distinct blue outline star (not the filled amber star VIP uses) — same "star" shape reads as
 * related-but-different at a glance, since this is genuinely about the Google review link
 * specifically, not general customer value.
 */
export default function GoogleReviewClickedIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="Has clicked the Google review link before" className="inline-flex shrink-0 text-blue-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M12 2.5 14.9 9l7.1.6-5.4 4.6 1.7 6.9L12 17.8 5.7 21.1l1.7-6.9L2 9.6 9.1 9z" />
      </svg>
      <span className="sr-only">Has clicked the Google review link before</span>
    </span>
  );
}
