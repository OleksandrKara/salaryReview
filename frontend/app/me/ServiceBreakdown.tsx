'use client';

import { useState } from 'react';
import type { AttributedService, HalfSettlement, ProviderDetail } from '../lib/types';
import SalaryCopyButton from '../components/SalaryCopyButton';

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

// Provider's per-period service breakdown, collapsible to match the discounts section. Each period
// expands to the #salary block + the service lines + totals.
export default function ServiceBreakdown({ detail }: { detail: ProviderDetail }) {
  if (!detail.payout) return null;
  const first = detail.services.filter((s) => s.half === 'FIRST');
  const second = detail.services.filter((s) => s.half === 'SECOND');

  return (
    <section className="mt-8">
      <h2 className="mb-1 text-sm font-semibold">Service breakdown</h2>
      <p className="mb-3 text-xs text-zinc-500">
        Every service this period, with discounts and cash notes, so you can check your numbers — plus
        the copy-pasteable #salary block.
      </p>
      <div className="flex flex-col gap-3">
        <HalfServices title="1–15" lines={first} settlement={detail.payout.firstHalf} message={detail.firstHalfMessage} />
        <HalfServices title="16–end" lines={second} settlement={detail.payout.secondHalf} message={detail.secondHalfMessage} />
      </div>
    </section>
  );
}

function HalfServices({
  title,
  lines,
  settlement,
  message,
}: {
  title: string;
  lines: AttributedService[];
  settlement: HalfSettlement;
  message: string | null;
}) {
  const [open, setOpen] = useState(false);
  const gross = lines.reduce((s, l) => s + l.gross, 0);
  const discount = lines.reduce((s, l) => s + l.discount, 0);

  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <div className="flex items-center justify-between px-4 py-3">
        <div>
          <span className="text-sm font-medium">{title}</span>
          <span className="ml-3 text-sm text-zinc-600">
            {lines.length} {lines.length === 1 ? 'service' : 'services'}
            <span className="text-zinc-400"> · gross {usd(gross)}{discount > 0 && ` · discounts ${usd(discount)}`}</span>
          </span>
        </div>
        {lines.length > 0 && (
          <button onClick={() => setOpen((o) => !o)} className="text-xs text-blue-600 hover:underline">
            {open ? 'Hide' : 'Show'} breakdown
          </button>
        )}
      </div>

      {open && lines.length > 0 && (
        <div className="flex flex-col gap-3 border-t border-zinc-200 p-4">
          {message && <SalaryCopyButton message={message} />}
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
        </div>
      )}
    </div>
  );
}
