'use client';

/**
 * "Has clicked the Yelp review link before" indicator — shared across the conversation list row,
 * thread header, and contact info panel, mirroring {@link GoogleReviewClickedIcon}/
 * {@link FeedbackFormClickedIcon}'s own convention (bare icon, title tooltip, sr-only text,
 * rendered only when true).
 *
 * A speech-bubble shape in rose — deliberately distinct from {@link GoogleReviewClickedIcon}'s
 * star (this app already uses an outline star for Google review and a filled star for VIP; a
 * third star here would blur all three together at a glance) and from
 * {@link FeedbackFormClickedIcon}'s clipboard-check.
 */
export default function YelpReviewClickedIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="Has clicked the Yelp review link before" className="inline-flex shrink-0 text-rose-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
      </svg>
      <span className="sr-only">Has clicked the Yelp review link before</span>
    </span>
  );
}
