import type { NoShowRow } from '../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function fmtDate(d: string) {
  const [y, m, day] = d.split('-').map(Number);
  return `${MONTHS[(m - 1) % 12]} ${day}`;
}

// Read-only no-show breakdown at the bottom of the provider's page (styled like the discounts/services
// breakdowns). Shows each no-show for the month and whether the $25 fee was collected — and credited.
export default function NoShowBreakdown({ rows }: { rows: NoShowRow[] }) {
  if (!rows || rows.length === 0) return null;
  const credited = rows.filter((r) => r.state === 'CREDITED' || r.state === 'CONFIRMED');
  const total = credited.reduce((s, r) => s + (r.feeAmount ?? 0), 0);

  return (
    <section className="mt-8">
      <h2 className="mb-1 text-sm font-semibold">No-shows</h2>
      <p className="mb-3 text-xs text-zinc-500">
        Your no-show appointments this month. When the salon collects the $25 cancellation fee, it&apos;s
        paid to you in full — these credits are already in your total above.
      </p>
      <div className="overflow-hidden rounded-lg ring-1 ring-zinc-200">
        <ul className="divide-y divide-zinc-100">
          {rows.map((r) => (
            <li key={`${r.bookingId}-${r.providerId}`} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
              <div className="min-w-0">
                <span className="font-medium">{fmtDate(r.noShowDate)}</span>
                {r.customer && <span className="text-zinc-500"> · {r.customer}</span>}
              </div>
              <FeeBadge row={r} />
            </li>
          ))}
        </ul>
        {total > 0 && (
          <div className="flex items-baseline justify-between border-t border-zinc-200 bg-zinc-50 px-4 py-2 text-xs font-medium">
            <span>No-show fees credited to you</span>
            <span className="tabular-nums text-yellow-800">+{usd(total)}</span>
          </div>
        )}
      </div>
    </section>
  );
}

function FeeBadge({ row }: { row: NoShowRow }) {
  if (row.state === 'CREDITED' || row.state === 'CONFIRMED') {
    return (
      <span className="shrink-0 rounded bg-yellow-50 px-2 py-0.5 text-xs font-medium text-yellow-800 ring-1 ring-yellow-300">
        fee paid +{usd(row.feeAmount ?? 25)}
      </span>
    );
  }
  return <span className="shrink-0 text-xs text-zinc-400">no fee collected</span>;
}
