'use client';

import { useState } from 'react';
import type { RetentionSeriesPoint } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Stacked-bar chart of new (accent) vs returning (zinc) clients per month. Pure CSS bars (no chart lib);
// mobile-friendly — bars flex to fill, scroll horizontally only if the range is very long. `newLabel`
// lets a salon-level view read "Fresh vs returning" while the provider view reads "New vs returning".
//
// Tapping/clicking (or keyboard-focusing) a column selects it and shows that month's exact numbers in a
// readout line under the header — a readout rather than a floating bubble so it never clips inside the
// card or the horizontal-scroll area, and it reads clearly on a phone.
export default function NewReturningChart({
  points,
  label,
  newLabel = 'New',
  testId = 'retention-chart',
}: {
  points: RetentionSeriesPoint[];
  label: string;
  newLabel?: string;
  testId?: string;
}) {
  const [active, setActive] = useState<number | null>(null);

  const max = Math.max(1, ...points.map((p) => p.clientsSeen));
  const totalNew = points.reduce((s, p) => s + p.newClients, 0);
  const totalRet = points.reduce((s, p) => s + p.returningClients, 0);
  const newWord = newLabel.toLowerCase();

  // Up to ~12 months fit a phone width; beyond that, let the chart scroll horizontally rather than
  // squeezing bars into slivers.
  const scrolls = points.length > 12;
  const sel = active != null ? points[active] : null;

  return (
    <div data-testid={testId} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="text-sm font-medium text-zinc-700">{newLabel} vs returning clients — {label}</span>
        <span className="flex items-center gap-3 text-xs text-zinc-500">
          <Legend className="bg-emerald-500" text={`${newLabel} (${totalNew})`} />
          <Legend className="bg-zinc-300" text={`Returning (${totalRet})`} />
        </span>
      </div>

      {/* Readout for the selected column (or a hint to tap). aria-live so it's announced on selection. */}
      <p
        data-testid={`${testId}-readout`}
        aria-live="polite"
        className="mb-3 min-h-[1.25rem] text-xs tabular-nums text-zinc-500"
      >
        {sel ? (
          <>
            <span className="font-medium text-zinc-700">{MONTHS[sel.month - 1]} {sel.year}</span>
            {' · '}
            <span className="font-medium text-emerald-600">{sel.newClients} {newWord}</span>
            {' · '}
            <span className="font-medium text-zinc-600">{sel.returningClients} returning</span>
            {' · '}
            {sel.newClients + sel.returningClients} total
          </>
        ) : (
          <span className="text-zinc-400">Tap a bar to see that month&apos;s numbers.</span>
        )}
      </p>

      <div className={scrolls ? '-mx-1 overflow-x-auto px-1' : ''}>
        <div
          className="flex items-end gap-[2px] sm:gap-1"
          style={scrolls ? { minWidth: `${points.length * 26}px` } : undefined}
        >
          {points.map((p, i) => {
            const total = p.newClients + p.returningClients;
            const isActive = active === i;
            return (
              <button
                key={`${p.year}-${p.month}`}
                type="button"
                aria-pressed={isActive}
                aria-label={`${MONTHS[p.month - 1]} ${p.year}: ${p.newClients} ${newWord}, ${p.returningClients} returning, ${total} total`}
                title={`${MONTHS[p.month - 1]} ${p.year} · ${p.newClients} ${newWord} · ${p.returningClients} returning · ${total} total`}
                onClick={() => setActive((cur) => (cur === i ? null : i))}
                onFocus={() => setActive(i)}
                className="flex flex-1 cursor-pointer flex-col items-center gap-1 rounded-b bg-transparent p-0 focus:outline-none"
              >
                <div
                  className={`flex h-32 w-full max-w-[2.75rem] flex-col justify-end overflow-hidden rounded-t transition-all sm:h-40 ${
                    isActive ? 'ring-2 ring-zinc-500 ring-offset-1' : 'hover:opacity-90'
                  }`}
                >
                  <div className="w-full bg-emerald-500" style={{ height: `${(p.newClients / max) * 100}%` }} />
                  <div className="w-full bg-zinc-300" style={{ height: `${(p.returningClients / max) * 100}%` }} />
                </div>
                <span
                  className={`text-center text-[9px] leading-tight ${isActive ? 'font-semibold text-zinc-600' : 'text-zinc-400'}`}
                >
                  {MONTHS[p.month - 1]}
                  {p.month === 1 || i === 0 ? (
                    <span className="block text-[8px] text-zinc-300">{`’${String(p.year).slice(2)}`}</span>
                  ) : null}
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function Legend({ className, text }: { className: string; text: string }) {
  return (
    <span className="flex items-center gap-1">
      <span className={`inline-block h-2.5 w-2.5 rounded-sm ${className}`} /> {text}
    </span>
  );
}
