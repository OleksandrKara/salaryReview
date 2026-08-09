'use client';

/**
 * "Number blocked" indicator — shared across the conversation list row, thread header, and
 * contact info panel, mirroring {@link VipIcon}/{@link SmsConsentIcon}/{@link
 * NegativeFeedbackIcon}'s own convention (bare icon, title tooltip, sr-only text, rendered only
 * when true).
 *
 * Two sources render differently so a manager can tell them apart at a glance:
 * - `optedOut` (the customer texted STOP/UNSUBSCRIBE/...) renders the literal 🚫 emoji — a
 *   legally binding opt-out is a different situation from a manager's own judgment call, and
 *   deserves to visually stand out rather than blend in with the rest of this icon set's uniform
 *   monochrome SVGs.
 * - A manual block (a manager chose "Block number" from the conversation menu) keeps the original
 *   red no-entry SVG.
 *
 * Both stop TwilioSmsService from sending this number any further outbound SMS, automated or
 * manual — see BlockedNumber#source on the backend for how the two are distinguished.
 */
export default function BlockedIcon({ optedOut = false, size = 14 }: { optedOut?: boolean; size?: number }) {
  const title = optedOut ? 'Opted out — replied STOP' : 'Number blocked';
  if (optedOut) {
    return (
      <span title={title} className="inline-flex shrink-0" style={{ fontSize: size }}>
        🚫
        <span className="sr-only">{title}</span>
      </span>
    );
  }
  return (
    <span title={title} className="inline-flex shrink-0 text-red-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <circle cx="12" cy="12" r="10" />
        <path d="m4.9 4.9 14.2 14.2" />
      </svg>
      <span className="sr-only">{title}</span>
    </span>
  );
}
