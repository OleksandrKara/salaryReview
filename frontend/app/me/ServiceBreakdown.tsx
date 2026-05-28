'use client';

import { useState } from 'react';
import type { AttributedService, HalfSettlement, ProviderDetail } from '../lib/types';
import SalaryCopyButton from '../components/SalaryCopyButton';
import ServiceLinesTable from '../components/ServiceLinesTable';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

// Provider's per-period service breakdown, collapsible to match the discounts section. Each period
// expands to the #salary block + the service lines (grouped by appointment) + totals.
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
        <HalfServices title="1–15" lines={first} settlement={detail.payout.firstHalf} message={detail.firstHalfMessage} tierApplied={detail.payout.tierApplied} baseRate={detail.payout.firstHalf.appliedRate} />
        <HalfServices title="16–end" lines={second} settlement={detail.payout.secondHalf} message={detail.secondHalfMessage} tierApplied={detail.payout.tierApplied} baseRate={detail.payout.firstHalf.appliedRate} />
      </div>
    </section>
  );
}

function HalfServices({
  title,
  lines,
  settlement,
  message,
  tierApplied,
  baseRate,
}: {
  title: string;
  lines: AttributedService[];
  settlement: HalfSettlement;
  message: string | null;
  tierApplied: boolean;
  baseRate: number;
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
          <ServiceLinesTable lines={lines} settlement={settlement} tierApplied={tierApplied} baseRate={baseRate} />
        </div>
      )}
    </div>
  );
}
