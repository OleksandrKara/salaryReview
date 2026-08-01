'use client';

/**
 * "Number blocked" indicator — shared across the conversation list row and thread header,
 * mirroring {@link VipIcon}/{@link SmsConsentIcon}/{@link NegativeFeedbackIcon}'s own convention
 * (bare icon, title tooltip, sr-only text, rendered only when true). Set once a manager has
 * blocked this number via the conversation menu — see TwilioSmsService, which refuses to send it
 * any further outbound SMS (automated or manual).
 *
 * Red — the one icon in this set that's a hard "stop", not just a "handle with care" flag like the
 * amber VIP/negative-feedback icons.
 */
export default function BlockedIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="Number blocked" className="inline-flex shrink-0 text-red-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <circle cx="12" cy="12" r="10" />
        <path d="m4.9 4.9 14.2 14.2" />
      </svg>
      <span className="sr-only">Number blocked</span>
    </span>
  );
}
