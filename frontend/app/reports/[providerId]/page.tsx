import Link from 'next/link';
import { serverApi } from '../../lib/serverApi';
import type { AttributedService, HalfSettlement, ProviderDetail } from '../../lib/types';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function ChannelTag({ channel }: { channel: string }) {
  const map: Record<string, string> = {
    CARD: 'bg-blue-50 text-blue-700 ring-blue-200',
    CASH: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    'CASH-NOTE': 'bg-amber-50 text-amber-700 ring-amber-200',
  };
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ring-1 ${map[channel] ?? 'bg-zinc-100 text-zinc-600 ring-zinc-300'}`}>{channel}</span>;
}

// Next 16: params and searchParams are Promises.
export default async function ProviderDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ providerId: string }>;
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const { providerId } = await params;
  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;

  const detail: ProviderDetail = await serverApi.getProviderDetail(year, month, Number(providerId));
  const backHref = `/reports?year=${year}&month=${month}`;

  const firstHalf = detail.services.filter((s) => s.half === 'FIRST');
  const secondHalf = detail.services.filter((s) => s.half === 'SECOND');

  return (
    <main className="mx-auto max-w-5xl p-8">
      <div className="mb-1 flex items-baseline gap-3">
        <Link href={backHref} className="text-sm text-zinc-500 hover:text-zinc-800">← Report</Link>
        <h1 className="text-2xl font-semibold">{detail.name ?? 'Provider'}</h1>
        <span className="text-sm text-zinc-500">{MONTHS[month - 1]} {year}</span>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        Line-by-line trace — gross is the menu price the payout uses; discount and net are what Square
        actually charged. Use the unattributed section below to chase a payment that looks missing.
      </p>

      {!detail.payout ? (
        <p className="mt-8 text-center text-zinc-400">No activity for this provider this month.</p>
      ) : (
        <div className="flex flex-col gap-8">
          <HalfSection title="1–15" lines={firstHalf} settlement={detail.payout.firstHalf} />
          <HalfSection title="16–end" lines={secondHalf} settlement={detail.payout.secondHalf} />
        </div>
      )}

      <section className="mt-10">
        <h2 className="mb-1 text-sm font-semibold">Unattributed sales ({detail.unmatched.length})</h2>
        <p className="mb-2 text-xs text-zinc-500">
          Paid order lines Square couldn&apos;t tie to any provider&apos;s booking (no matching
          appointment). Salon-wide — could belong to anyone. The usual suspect for a &ldquo;missing&rdquo; payment.
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
    </main>
  );
}

function HalfSection({ title, lines, settlement }: { title: string; lines: AttributedService[]; settlement: HalfSettlement }) {
  const gross = lines.reduce((s, l) => s + l.gross, 0);
  const discount = lines.reduce((s, l) => s + l.discount, 0);
  return (
    <section>
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold">{title}</h2>
        <span className="text-xs text-zinc-500">
          {lines.length} lines · gross {usd(gross)}{discount > 0 && ` · discounts ${usd(discount)}`}
        </span>
      </div>
      <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Date</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2">Channel</th>
              <th className="px-3 py-2 text-right">Gross</th>
              <th className="px-3 py-2 text-right">Discount</th>
              <th className="px-3 py-2 text-right">Net</th>
              <th className="px-3 py-2 text-center">Counts</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {lines.map((l, i) => (
              <tr key={i} className="hover:bg-zinc-50">
                <td className="px-3 py-2 tabular-nums text-zinc-600">{l.date}</td>
                <td className="px-3 py-2">
                  <span className="flex items-center gap-2">
                    {l.service}
                    {l.prepaid && <span className="rounded bg-violet-50 px-1.5 py-0.5 text-[10px] font-medium text-violet-700 ring-1 ring-violet-200">prepaid</span>}
                  </span>
                </td>
                <td className="px-3 py-2"><ChannelTag channel={l.channel} /></td>
                <td className="px-3 py-2 text-right tabular-nums">{usd(l.gross)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{l.discount > 0 ? `−${usd(l.discount)}` : '—'}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{usd(l.net)}</td>
                <td className="px-3 py-2 text-center">{l.counted ? '✓' : <span className="text-zinc-300">—</span>}</td>
              </tr>
            ))}
            {lines.length === 0 && (
              <tr><td colSpan={7} className="px-3 py-4 text-center text-zinc-400">No services this period.</td></tr>
            )}
          </tbody>
          <tfoot className="border-t border-zinc-200 bg-zinc-50 text-xs">
            <tr>
              <td className="px-3 py-2 font-medium" colSpan={2}>
                Counted: {settlement.countedServices} · rate {Math.round(settlement.appliedRate * 100)}%
              </td>
              <td className="px-3 py-2 text-right text-zinc-500">card {usd(settlement.cardRevenue)}</td>
              <td className="px-3 py-2 text-right text-zinc-500" colSpan={2}>
                tips {usd(settlement.tipsAfterFee)}{settlement.tierBonus > 0 && ` · bonus ${usd(settlement.tierBonus)}`}
              </td>
              <td className="px-3 py-2 text-right font-semibold" colSpan={2}>→ {usd(settlement.zelleToProvider)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
