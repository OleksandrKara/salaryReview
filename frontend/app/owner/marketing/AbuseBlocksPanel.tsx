import type { AbuseBlockEntry, AbuseBlocksData } from '../../lib/types';

const REASON_LABELS: Record<string, string> = {
  honeypot: 'Honeypot field filled',
  too_fast: 'Submitted too fast',
  rate_limit_phone: 'Rate limit (phone)',
  rate_limit_ip: 'Rate limit (IP)',
  turnstile_failed: 'Failed bot check',
};

const fmtDate = (iso: string) =>
  new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

function ReasonBadge({ reason }: { reason: string }) {
  return (
    <span className="whitespace-nowrap rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700 ring-1 ring-inset ring-red-200">
      {REASON_LABELS[reason] ?? reason}
    </span>
  );
}

function RecentBlocksList({ recent }: { recent: AbuseBlockEntry[] }) {
  return (
    <ul className="flex flex-col gap-1.5">
      {recent.map((b, i) => (
        <li key={i} className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-white px-3 py-2 text-xs ring-1 ring-zinc-100">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-zinc-500">{fmtDate(b.occurredAt)}</span>
            <span className="text-zinc-400">{b.endpoint}</span>
            {b.phoneNumber && <span className="text-zinc-500">{b.phoneNumber}</span>}
            {b.ipAddress && <span className="text-zinc-400">{b.ipAddress}</span>}
          </div>
          <ReasonBadge reason={b.reason} />
        </li>
      ))}
    </ul>
  );
}

export default function AbuseBlocksPanel({ data }: { data: AbuseBlocksData }) {
  if (!data.available) return null; // schema not reachable yet — same as other marketing panels, just don't render

  const reasons = Object.entries(data.countsByReasonLast24h);
  const total = reasons.reduce((sum, [, count]) => sum + count, 0);

  return (
    <div className="mt-6">
      <h2 className="mb-2 text-sm font-medium text-zinc-500">Blocked booking attempts (last 24h)</h2>
      {total === 0 ? (
        <p className="text-xs text-zinc-400">No blocked attempts in the last 24 hours.</p>
      ) : (
        <div className="mb-3 flex flex-wrap gap-1.5">
          {reasons.map(([reason, count]) => (
            <span key={reason} className="inline-flex items-center gap-1 rounded-full bg-zinc-100 px-2.5 py-1 text-xs font-medium text-zinc-700 ring-1 ring-inset ring-zinc-200">
              {REASON_LABELS[reason] ?? reason}: {count}
            </span>
          ))}
        </div>
      )}
      {data.recent.length > 0 && (
        <details className="mt-1">
          <summary className="cursor-pointer text-xs font-medium text-blue-600 hover:underline">
            View recent blocked attempts ({data.recent.length})
          </summary>
          <div className="mt-2">
            <RecentBlocksList recent={data.recent} />
          </div>
        </details>
      )}
    </div>
  );
}
