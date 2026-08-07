'use client';

/**
 * "Carrier flagged this number's texts as spam, or the recipient opted out" indicator — shared
 * across the conversation list row and thread header, mirroring {@link BlockedIcon}'s own
 * convention (bare icon, title tooltip, sr-only text, rendered only when true). Set when any
 * outbound message to this number ever came back with Twilio delivery-status error code 30007
 * ("Filtered as spam by carrier") or 21610 ("Recipient has opted out — replied STOP") — see
 * SmsMessageLogService#DELIVERY_ERROR_MESSAGES and #phoneNumbersFlaggedAsSpam. The full reason and
 * date live on the individual message bubble ("Not delivered — ..."); this is just the
 * quick-glance version so a manager scanning the list doesn't have to open every thread to notice.
 *
 * Red, like {@link BlockedIcon} — this is also effectively a hard stop (Twilio itself refuses
 * further sends to a 21610 number), just carrier/system-detected rather than a manager's own
 * choice. A warning-triangle shape keeps it visually distinct from Blocked's circle-slash even
 * though both share the color.
 */
export default function SpamFlagIcon({ size = 14 }: { size?: number }) {
  return (
    <span title="Flagged as spam by carrier, or recipient opted out" className="inline-flex shrink-0 text-red-600">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="m10.29 3.86-8.18 14.18A2 2 0 0 0 3.82 21h16.36a2 2 0 0 0 1.71-2.96L13.71 3.86a2 2 0 0 0-3.42 0Z" />
        <path d="M12 9v4" />
        <path d="M12 17h.01" />
      </svg>
      <span className="sr-only">Flagged as spam by carrier, or recipient opted out</span>
    </span>
  );
}
