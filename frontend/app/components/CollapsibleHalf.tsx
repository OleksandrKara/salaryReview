'use client';

import { useState } from 'react';
import type { AttributedService, HalfSettlement } from '../lib/types';
import SalaryCopyButton from './SalaryCopyButton';
import ServiceLinesTable from './ServiceLinesTable';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

// One half-period as a collapsible card: header summary + show/hide; expands to the #salary block
// and the appointment-grouped service table. Shared by the owner drill-down and the provider /me view.
export default function CollapsibleHalf({
  title,
  lines,
  settlement,
  message,
  tierApplied,
  baseRate,
  defaultOpen = false,
  showSalary = true,
}: {
  title: string;
  lines: AttributedService[];
  settlement: HalfSettlement;
  message: string | null;
  tierApplied: boolean;
  baseRate: number;
  defaultOpen?: boolean;
  showSalary?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const gross = lines.reduce((s, l) => s + l.gross, 0);
  const discount = lines.reduce((s, l) => s + l.discount, 0);
  const tip = lines.reduce((s, l) => s + l.tip, 0);

  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <div className="flex items-center justify-between gap-4 px-4 py-3">
        <div className="min-w-0">
          <span className="text-sm font-medium">{title}</span>
          <span className="ml-3 text-sm text-zinc-600">
            {lines.length} {lines.length === 1 ? 'service' : 'services'}
            <span className="text-zinc-400"> · gross {usd(gross)}{discount > 0 && ` · discounts ${usd(discount)}`}{tip > 0 && ` · tips ${usd(tip)}`}</span>
          </span>
        </div>
        {lines.length > 0 && (
          <button onClick={() => setOpen((o) => !o)} className="shrink-0 whitespace-nowrap text-xs text-blue-600 hover:underline">
            {open ? 'Hide' : 'Show'} breakdown
          </button>
        )}
      </div>

      {open && lines.length > 0 && (
        <div className="flex flex-col gap-3 border-t border-zinc-200 p-4">
          {showSalary && message && <SalaryCopyButton message={message} />}
          <ServiceLinesTable lines={lines} settlement={settlement} tierApplied={tierApplied} baseRate={baseRate} />
        </div>
      )}
    </div>
  );
}
