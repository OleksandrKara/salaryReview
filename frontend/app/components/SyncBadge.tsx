import { SyncButton } from './SyncButton';

// "Synced with Square" chip showing when the data was actually pulled. Reads are cached briefly for
// speed, so syncedAt is the real last-fetch time (not render time); the Sync button forces a fresh
// pull. Time shown in the salon's timezone.
export function SyncBadge({ syncedAt, timezone }: { syncedAt: string; timezone?: string }) {
  const d = new Date(syncedAt);
  const time = isNaN(d.getTime())
    ? ''
    : d.toLocaleString('en-US', {
        timeZone: timezone || undefined,
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
      });
  return (
    <span className="inline-flex items-center gap-2">
      <span
        title="Pulled from Square and cached briefly for speed. The time is when it was last fetched — hit Sync to pull fresh."
        className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 ring-1 ring-emerald-200"
      >
        <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" aria-hidden="true">
          <rect x="3" y="3" width="18" height="18" rx="4" fill="currentColor" />
          <rect x="9" y="9" width="6" height="6" rx="1.4" fill="#ecfdf5" />
        </svg>
        Synced with Square{time && <span className="font-normal text-emerald-600">· {time}</span>}
      </span>
      <SyncButton />
    </span>
  );
}
