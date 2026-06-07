'use client';

import { useState } from 'react';
import type { MonthSummary } from '../../lib/types';

export type Channel = 'all' | 'card' | 'cash';

export function channelValue(m: MonthSummary, ch: Channel): number | null {
  if (ch === 'card') return m.cardRevenue;
  if (ch === 'cash') return m.cashRevenue;
  return m.grossRevenue;
}

function compact(n: number) {
  if (n >= 1000) return `$${(n / 1000).toFixed(n >= 10000 ? 0 : 1).replace(/\.0$/, '')}k`;
  return `$${Math.round(n)}`;
}

function momPct(months: MonthSummary[], i: number, ch: Channel): number | null {
  if (i === 0) return null;
  const cur  = channelValue(months[i],     ch);
  const prev = channelValue(months[i - 1], ch);
  if (cur == null || prev == null || prev === 0) return null;
  return ((cur - prev) / prev) * 100;
}

export default function RevenueChart({
  months,
  channel,
  onChannelChange,
}: {
  months: MonthSummary[];
  channel: Channel;
  onChannelChange: (ch: Channel) => void;
}) {
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);

  const values = months.map((m) => channelValue(m, channel) ?? 0);
  const max = Math.max(...values, 1);

  const guides = [0, 0.25, 0.5, 0.75, 1].map((f) => ({
    pct: f * 100,
    label: f === 0 ? '' : compact(max * f),
  }));

  const prevYears = new Set<number>();

  const btn = (ch: Channel) =>
    `px-3 py-1 text-xs font-medium rounded-md transition-colors ${
      channel === ch
        ? 'bg-zinc-800 text-white'
        : 'text-zinc-500 hover:text-zinc-700'
    }`;

  return (
    <div>
      {/* Channel toggle */}
      <div className="mb-4 flex items-center gap-1 rounded-lg bg-zinc-100 p-1 w-fit">
        <button data-testid="chart-channel-all"  className={btn('all')}  onClick={() => onChannelChange('all')}>All</button>
        <button data-testid="chart-channel-card" className={btn('card')} onClick={() => onChannelChange('card')}>Card</button>
        <button data-testid="chart-channel-cash" className={btn('cash')} onClick={() => onChannelChange('cash')}>Cash</button>
      </div>

      {/* MoM % row — hidden on mobile to avoid crowding narrow bars */}
      <div className="mb-1 hidden pl-10 sm:flex">
        {months.map((m, i) => {
          const pct = momPct(months, i, channel);
          const val = channelValue(m, channel);
          const isEmpty = val == null || val === 0;
          return (
            <div key={`mom-${m.year}-${m.month}`} className="flex flex-1 justify-center">
              {!isEmpty && pct != null ? (
                <span
                  className={`text-[9px] font-semibold leading-none ${
                    pct >= 0 ? 'text-emerald-600' : 'text-rose-500'
                  }`}
                >
                  {pct >= 0 ? '+' : ''}{pct.toFixed(1)}%
                </span>
              ) : (
                <span className="text-[9px] text-zinc-200">—</span>
              )}
            </div>
          );
        })}
      </div>

      {/* Chart */}
      <div className="flex gap-2">
        {/* Y-axis — hidden on mobile */}
        <div className="relative hidden w-8 shrink-0 sm:block" style={{ height: '200px' }}>
          {guides.filter(g => g.label).map((g) => (
            <span
              key={g.pct}
              className="absolute right-0 text-[9px] leading-none text-zinc-300"
              style={{ bottom: `calc(${g.pct}% - 5px)` }}
            >
              {g.label}
            </span>
          ))}
        </div>

        {/* Bars */}
        <div className="relative flex-1">
          {/* Guide lines */}
          <div className="pointer-events-none absolute inset-0" style={{ height: '200px' }}>
            {guides.map((g) => (
              <div
                key={g.pct}
                className={`absolute w-full border-t ${g.pct === 0 ? 'border-zinc-200' : 'border-zinc-100'}`}
                style={{ bottom: `${g.pct}%` }}
              />
            ))}
          </div>

          {/* Bar columns */}
          <div className="relative flex items-end gap-0.5 sm:gap-1" style={{ height: '200px' }}>
            {months.map((m, i) => {
              const val  = channelValue(m, channel) ?? 0;
              const pct  = max > 0 ? (val / max) * 100 : 0;
              const pctMom = momPct(months, i, channel);
              const isLive   = !m.finalized && val > 0;
              const isEmpty  = m.grossRevenue == null;
              const isHovered = hoveredIndex === i;
              const showYear = !prevYears.has(m.year);
              if (showYear) prevYears.add(m.year);

              // Bar color: zinc palette throughout; live is lighter to signal estimate
              let barCls = 'bg-zinc-700';
              if (isLive)         barCls = 'bg-zinc-400';
              else if (isHovered) barCls = 'bg-zinc-900';

              return (
                <div
                  key={`bar-${m.year}-${m.month}`}
                  className="group relative flex flex-1 flex-col items-center"
                  style={{ height: '100%' }}
                  onMouseEnter={() => setHoveredIndex(i)}
                  onMouseLeave={() => setHoveredIndex(null)}
                >
                  {/* Tooltip */}
                  {isHovered && !isEmpty && (
                    <div className="pointer-events-none absolute bottom-full mb-8 z-10 whitespace-nowrap rounded-lg bg-zinc-900 px-3 py-2 text-xs text-white shadow-xl">
                      <div className="font-semibold">{m.label} {m.year}</div>
                      <div className="mt-0.5 text-zinc-300">
                        {val.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })}
                        {isLive && <span className="ml-1 text-zinc-400">live</span>}
                      </div>
                      {pctMom != null && (
                        <div className={`mt-0.5 ${pctMom >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                          {pctMom >= 0 ? '+' : ''}{pctMom.toFixed(1)}% vs prior month
                        </div>
                      )}
                    </div>
                  )}

                  {/* Bar */}
                  <div className="flex w-full flex-1 flex-col items-center justify-end">
                    {isEmpty ? (
                      <div className="w-full rounded-sm bg-zinc-100" style={{ height: '3px' }} />
                    ) : (
                      <div
                        className={`w-full rounded-t transition-all duration-200 ${barCls}`}
                        style={{ height: `${Math.max(pct, 2)}%` }}
                      />
                    )}
                  </div>

                  {/* Month + year label */}
                  <div className="mt-1.5 flex flex-col items-center">
                    <span className="text-[10px] leading-none text-zinc-500 group-hover:text-zinc-800 transition-colors">
                      {m.label}
                    </span>
                    {showYear && (
                      <span className="mt-0.5 text-[9px] leading-none text-zinc-300">
                        '{String(m.year).slice(2)}
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
