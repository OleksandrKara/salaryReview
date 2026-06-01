import type { ProviderDetail } from '../lib/types';
import CollapsibleHalf from './CollapsibleHalf';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function ChannelTag({ channel }: { channel: string }) {
  const map: Record<string, string> = {
    CARD: 'bg-blue-50 text-blue-700 ring-blue-200',
    CASH: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    'CASH-NOTE': 'bg-amber-50 text-amber-700 ring-amber-200',
    PREPAID: 'bg-violet-50 text-violet-700 ring-violet-200',
    COMP: 'bg-rose-50 text-rose-700 ring-rose-200',
    REDO: 'bg-orange-50 text-orange-700 ring-orange-200',
  };
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ring-1 ${map[channel] ?? 'bg-zinc-100 text-zinc-600 ring-zinc-300'}`}>{channel}</span>;
}

// Per-provider line-level trace shared by the owner/manager drill-down and the provider self-view.
// Each half is a collapsible card (show/hide). `showUnmatched` controls the salon-wide unattributed
// section (owner/manager only — it references other customers).
export default function ProviderTrace({
  detail,
  showUnmatched,
}: {
  detail: ProviderDetail;
  showUnmatched: boolean;
}) {
  const firstHalf = detail.services.filter((s) => s.half === 'FIRST');
  const secondHalf = detail.services.filter((s) => s.half === 'SECOND');

  return (
    <div className="flex flex-col gap-3">
      {detail.payout && (
        <>
          <CollapsibleHalf title="1–15" lines={firstHalf} settlement={detail.payout.firstHalf} message={detail.firstHalfMessage} tierApplied={detail.payout.tierApplied} baseRate={detail.payout.firstHalf.appliedRate} />
          <CollapsibleHalf title="16–end" lines={secondHalf} settlement={detail.payout.secondHalf} message={detail.secondHalfMessage} tierApplied={detail.payout.tierApplied} baseRate={detail.payout.firstHalf.appliedRate} />
        </>
      )}

      {showUnmatched && (
        <section className="mt-5">
          <h2 className="mb-1 text-sm font-semibold">Unattributed sales ({detail.unmatched.length})</h2>
          <p className="mb-2 text-xs text-zinc-500">
            Paid order lines Square couldn&apos;t tie to any provider&apos;s booking. Salon-wide — could
            belong to anyone. The usual suspect for a &ldquo;missing&rdquo; payment.
          </p>
          {detail.unmatched.length === 0 ? (
            <p className="text-xs text-zinc-400">None — every paid line matched a booking.</p>
          ) : (
            <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
              <table className="w-full text-sm">
                <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
                  <tr>
                    <th className="px-3 py-2">Date</th>
                    <th className="px-3 py-2">Service</th>
                    <th className="px-3 py-2">Channel</th>
                    <th className="px-3 py-2 text-right">Gross</th>
                    <th className="px-3 py-2">Customer</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-100">
                  {detail.unmatched.map((u, i) => (
                    <tr key={i} className="hover:bg-zinc-50">
                      <td className="px-3 py-2 tabular-nums text-zinc-600">{u.date}</td>
                      <td className="px-3 py-2">{u.service}</td>
                      <td className="px-3 py-2"><ChannelTag channel={u.channel} /></td>
                      <td className="px-3 py-2 text-right tabular-nums">{usd(u.gross)}</td>
                      <td className="px-3 py-2">
                        {u.customerId ? (
                          <a
                            href={`https://app.squareup.com/dashboard/customers/directory/customer/${u.customerId}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-blue-600 hover:underline"
                          >
                            {u.customerName ?? 'View in Square ↗'}
                          </a>
                        ) : (
                          <span className="text-zinc-400">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
