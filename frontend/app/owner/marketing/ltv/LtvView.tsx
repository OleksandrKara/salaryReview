'use client';

import { useState } from 'react';
import type { ChannelLtv, MarketingLtvData } from '../../../lib/types';
import { SOURCE_LABELS } from '../TrafficSourceFilter';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usdExact = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

const CHANNEL_LABELS: Record<string, string> = {
  ...SOURCE_LABELS,
  other: 'Other',
};

// One accent per channel — used for the mini share-of-revenue bar so a channel reads as the same
// color everywhere on this page, not reassigned row to row.
const CHANNEL_COLORS: Record<string, string> = {
  meta_ads: '#4f46e5', // indigo
  google_ads: '#0891b2', // cyan
  instagram_organic: '#db2777', // pink
  google_organic: '#16a34a', // green
  direct: '#71717a', // zinc
  other: '#a1a1aa',
};

export default function LtvView({
  initialData,
  slug,
}: {
  initialData: MarketingLtvData;
  slug?: string;
}) {
  const [data, setData] = useState(initialData);
  // Same "adopt fresh server props after a router.refresh()" pattern the other marketing tabs use
  // (see AdsReportView) — MarketingTabs' "Sync appointments" button re-runs this route's server
  // component, and a plain useState(initialData) alone would never notice the new prop.
  const [prevInitialData, setPrevInitialData] = useState(initialData);
  if (initialData !== prevInitialData) {
    setPrevInitialData(initialData);
    setData(initialData);
  }

  if (data.totals.customerCount === 0) {
    return (
      <div className="mt-2 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
        No paying customers yet for {slug ?? 'this page'} — lifetime value fills in as real
        bookings get paid.
      </div>
    );
  }

  // Only channels with at least one real customer are ranked/shown as bars — a channel that's
  // never converted anyone yet (customerCount 0) is still listed (so "nothing from Google Ads
  // yet" is visible), just without a share bar or "Best LTV" eligibility, at the bottom.
  const withCustomers = data.channels.filter((c) => c.customerCount > 0);
  const withoutCustomers = data.channels.filter((c) => c.customerCount === 0);
  const sorted = [...withCustomers].sort((a, b) => b.totalRevenue - a.totalRevenue);
  const bestLtvChannel = withCustomers.reduce<ChannelLtv | null>(
    (best, c) => (c.averageLtv != null && (best === null || c.averageLtv > (best.averageLtv ?? -1)) ? c : best),
    null,
  );

  return (
    <div className="flex flex-col gap-6">
      {/* Summary row — the "All channels" totals, given its own prominent treatment rather than
          buried as just another table row. */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <SummaryCard label="Paying customers" value={String(data.totals.customerCount)} />
        <SummaryCard label="Total revenue" value={usd(data.totals.totalRevenue)} />
        <SummaryCard
          label="Avg. LTV / customer"
          value={data.totals.averageLtv == null ? '—' : usd(data.totals.averageLtv)}
          className="col-span-2 sm:col-span-1"
        />
      </div>

      {/* Mobile: stacked cards */}
      <div className="flex flex-col gap-2 sm:hidden">
        {[...sorted, ...withoutCustomers].map((c) => (
          <ChannelCard key={c.channel} channel={c} totalRevenue={data.totals.totalRevenue} isBest={c === bestLtvChannel} />
        ))}
      </div>

      {/* Desktop: table */}
      <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Channel</th>
              <th className="px-3 py-2 text-right font-medium">Customers</th>
              <th className="px-3 py-2 text-right font-medium">Total revenue</th>
              <th className="px-3 py-2 text-right font-medium">Avg. LTV</th>
              <th className="px-3 py-2 font-medium">Share of revenue</th>
            </tr>
          </thead>
          <tbody>
            {[...sorted, ...withoutCustomers].map((c) => {
              const share = data.totals.totalRevenue > 0 ? (c.totalRevenue / data.totals.totalRevenue) * 100 : 0;
              return (
                <tr key={c.channel} className="border-t border-zinc-100">
                  <td className="px-3 py-2.5 font-medium text-zinc-700">
                    <span className="inline-flex items-center gap-1.5">
                      <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: CHANNEL_COLORS[c.channel] ?? '#a1a1aa' }} />
                      {CHANNEL_LABELS[c.channel] ?? c.channel}
                      {c === bestLtvChannel && <BestLtvBadge />}
                    </span>
                  </td>
                  <td className="px-3 py-2.5 text-right tabular-nums text-zinc-600">{c.customerCount}</td>
                  <td className="px-3 py-2.5 text-right tabular-nums text-zinc-600">{usdExact(c.totalRevenue)}</td>
                  <td className="px-3 py-2.5 text-right tabular-nums font-semibold text-zinc-900">
                    {c.averageLtv == null ? '—' : usdExact(c.averageLtv)}
                  </td>
                  <td className="px-3 py-2.5">
                    <div className="h-1.5 w-full max-w-[140px] overflow-hidden rounded-full bg-zinc-100">
                      <div
                        className="h-full rounded-full"
                        style={{ width: `${share}%`, backgroundColor: CHANNEL_COLORS[c.channel] ?? '#a1a1aa' }}
                      />
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-zinc-200 font-semibold text-zinc-900">
              <td className="px-3 py-2.5">All channels</td>
              <td className="px-3 py-2.5 text-right tabular-nums">{data.totals.customerCount}</td>
              <td className="px-3 py-2.5 text-right tabular-nums">{usdExact(data.totals.totalRevenue)}</td>
              <td className="px-3 py-2.5 text-right tabular-nums">
                {data.totals.averageLtv == null ? '—' : usdExact(data.totals.averageLtv)}
              </td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}

function SummaryCard({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <div className={`rounded-lg p-3 ring-1 ring-zinc-200 sm:p-4 ${className ?? ''}`}>
      <div className="text-[10px] font-medium uppercase tracking-wide text-zinc-500 sm:text-xs">{label}</div>
      <div className="mt-1 text-lg font-semibold text-zinc-900 sm:text-2xl">{value}</div>
    </div>
  );
}

function BestLtvBadge() {
  return (
    <span className="inline-flex items-center rounded-full bg-emerald-100 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-700">
      Best LTV
    </span>
  );
}

function ChannelCard({
  channel, totalRevenue, isBest,
}: {
  channel: ChannelLtv;
  totalRevenue: number;
  isBest: boolean;
}) {
  const share = totalRevenue > 0 ? (channel.totalRevenue / totalRevenue) * 100 : 0;
  const color = CHANNEL_COLORS[channel.channel] ?? '#a1a1aa';
  return (
    <div className="rounded-lg p-3 ring-1 ring-zinc-200">
      <div className="flex items-center justify-between gap-2">
        <span className="inline-flex items-center gap-1.5 font-medium text-zinc-700">
          <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: color }} />
          {CHANNEL_LABELS[channel.channel] ?? channel.channel}
          {isBest && <BestLtvBadge />}
        </span>
        <span className="text-lg font-semibold tabular-nums text-zinc-900">
          {channel.averageLtv == null ? '—' : usdExact(channel.averageLtv)}
        </span>
      </div>
      {channel.customerCount > 0 && (
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-zinc-100">
          <div className="h-full rounded-full" style={{ width: `${share}%`, backgroundColor: color }} />
        </div>
      )}
      <div className="mt-2 flex items-center justify-between text-xs text-zinc-500">
        <span>{channel.customerCount} {channel.customerCount === 1 ? 'customer' : 'customers'}</span>
        <span className="tabular-nums">{usdExact(channel.totalRevenue)} total</span>
      </div>
    </div>
  );
}
