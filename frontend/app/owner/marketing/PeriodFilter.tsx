'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useState } from 'react';
import type { PeriodSelection, PeriodType } from './period';
import { todayIso, withPeriodParams } from './period';

const PERIOD_LABELS: Record<PeriodType, string> = {
  all: 'All',
  week: 'Weekly',
  month: 'Monthly',
  mtd: 'Month to date',
  custom: 'Custom',
};

const ORDER: PeriodType[] = ['all', 'week', 'month', 'mtd', 'custom'];

/** The one period-filter control shared by every marketing tab (Overview, Contacts, Funnel, Ads
 * Report) — All / Weekly / Monthly / Month to date (default) / Custom. Weekly/Monthly are shown
 * everywhere but only clickable where `enableWeekMonth` is true (Ads Report today) — visible so
 * the owner knows the option exists, disabled with an explanatory tooltip elsewhere rather than
 * hidden, per the "for now" framing of that rollout.
 *
 * Owns two things: which chip is *shown* as selected (`pendingPeriod`, local — lets clicking
 * "Custom" reveal the from/to inputs immediately without waiting on the parent) and syncing the
 * *confirmed* selection into the URL (`?period=&from=&to=`, merged into whatever other params are
 * already there — slug, etc. — never rebuilt from scratch) so navigating between tabs or reloading
 * the page keeps showing the same period. The actual data refetch is the caller's job via
 * `onChange` — called directly, not derived from the URL round-trip, so there's no extra render
 * cycle between a click and the fetch starting. */
export default function PeriodFilter({
  value, onChange, enableWeekMonth = false, disabled, timeZone,
}: {
  value: PeriodSelection;
  onChange: (next: PeriodSelection) => void;
  enableWeekMonth?: boolean;
  disabled?: boolean;
  // Phase 6.3: the business's real configured timezone — see period.ts's own doc.
  timeZone?: string;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const [pendingPeriod, setPendingPeriod] = useState<PeriodType>(value.period);
  // Adjusting state during render on a prop change — this file's own established pattern
  // elsewhere in this app (AdsReportView's prevInitialData, AppointmentLedger's
  // prevInitialFilter) — so an external change to the confirmed period (e.g. the browser's
  // back button restoring an older URL) resets which chip reads as selected.
  const [prevPeriod, setPrevPeriod] = useState(value.period);
  if (value.period !== prevPeriod) {
    setPrevPeriod(value.period);
    setPendingPeriod(value.period);
  }

  const [customFrom, setCustomFrom] = useState(value.period === 'custom' ? value.from ?? '' : '');
  const [customTo, setCustomTo] = useState(value.period === 'custom' ? value.to ?? '' : '');

  function pushUrl(next: PeriodSelection) {
    const params = withPeriodParams(new URLSearchParams(searchParams.toString()), next);
    router.push(`${pathname}?${params.toString()}`, { scroll: false });
  }

  function select(period: PeriodType) {
    setPendingPeriod(period);
    if (period === 'custom') return; // just reveals the from/to panel; Apply below does the rest
    const next: PeriodSelection = { period };
    onChange(next);
    pushUrl(next);
  }

  function applyCustom() {
    if (!customFrom || !customTo) return;
    const next: PeriodSelection = { period: 'custom', from: customFrom, to: customTo };
    onChange(next);
    pushUrl(next);
  }

  return (
    <div>
      <div className="inline-flex flex-wrap gap-1 rounded-lg bg-zinc-100 p-1">
        {ORDER.map((period) => {
          const isWeekMonth = period === 'week' || period === 'month';
          const chipDisabled = disabled || (isWeekMonth && !enableWeekMonth);
          return (
            <button
              key={period}
              type="button"
              onClick={() => select(period)}
              disabled={chipDisabled}
              title={isWeekMonth && !enableWeekMonth ? 'Available on Ads Report' : undefined}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
                pendingPeriod === period ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200' : 'text-zinc-500 hover:text-zinc-700'
              }`}
            >
              {PERIOD_LABELS[period]}
            </button>
          );
        })}
      </div>

      {pendingPeriod === 'custom' && (
        <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">From</span>
            <input
              type="date"
              value={customFrom}
              max={customTo || undefined}
              disabled={disabled}
              onChange={(e) => setCustomFrom(e.target.value)}
              className="rounded border border-zinc-300 px-2 py-1.5 text-xs disabled:opacity-50"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">To</span>
            <input
              type="date"
              value={customTo}
              min={customFrom || undefined}
              max={todayIso(timeZone)}
              disabled={disabled}
              onChange={(e) => setCustomTo(e.target.value)}
              className="rounded border border-zinc-300 px-2 py-1.5 text-xs disabled:opacity-50"
            />
          </label>
          <button
            type="button"
            onClick={applyCustom}
            disabled={!customFrom || !customTo || disabled}
            className="rounded bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Apply
          </button>
        </div>
      )}
    </div>
  );
}
