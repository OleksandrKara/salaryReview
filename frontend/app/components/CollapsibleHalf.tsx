'use client';

import { useState } from 'react';
import type { AttributedService, HalfSettlement, Language } from '../lib/types';
import { t, tf } from '../lib/i18n';
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
  showPayoutSplit = false,
  language = null,
}: {
  title: string;
  lines: AttributedService[];
  settlement: HalfSettlement;
  message: string | null;
  tierApplied: boolean;
  baseRate: number;
  defaultOpen?: boolean;
  showSalary?: boolean;
  /** See ServiceLinesTable's own doc — owner-only. */
  showPayoutSplit?: boolean;
  language?: Language | null;
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
            {tf(language, 'cbServiceCount', { n: lines.length })}
            <span className="text-zinc-400"> · {t(language, 'cbGross')} {usd(gross)}{discount > 0 && ` · ${t(language, 'cbDiscounts')} ${usd(discount)}`}{tip > 0 && ` · ${t(language, 'cbTips')} ${usd(tip)}`}</span>
          </span>
        </div>
        {lines.length > 0 && (
          <button onClick={() => setOpen((o) => !o)} className="shrink-0 whitespace-nowrap text-xs text-blue-600 hover:underline">
            {open ? t(language, 'cbHideBreakdown') : t(language, 'cbShowBreakdown')}
          </button>
        )}
      </div>

      {open && lines.length > 0 && (
        <div className="flex flex-col gap-3 border-t border-zinc-200 p-4">
          {showSalary && message && <SalaryCopyButton message={message} />}
          <ServiceLinesTable lines={lines} settlement={settlement} tierApplied={tierApplied} baseRate={baseRate} showPayoutSplit={showPayoutSplit} />
        </div>
      )}
    </div>
  );
}
