import { Fragment } from 'react';
import type { AttributedService, HalfSettlement } from '../lib/types';
import { AppointmentCell } from './AppointmentCell';
import { groupByDay, formatDay } from '../lib/grouping';

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

// Service lines for one half, grouped by appointment: a header row per visit (date · time · client,
// linked once to Square) followed by that visit's services. Shared by the owner drill-down and /me.
export default function ServiceLinesTable({
  lines,
  settlement,
  tierApplied,
  baseRate,
}: {
  lines: AttributedService[];
  settlement: HalfSettlement;
  tierApplied: boolean; // whether the month qualifies for 50/50 (earned or granted)
  baseRate: number; // the base rate every half's card is actually paid at (e.g. 0.45)
}) {
  const days = groupByDay(lines);
  return (
    <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
      <table className="w-full text-sm">
        <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
          <tr>
            <th className="px-3 py-2">Service</th>
            <th className="px-3 py-2">Channel</th>
            <th className="px-3 py-2 text-right">Gross</th>
            <th className="px-3 py-2 text-right">Discount</th>
            <th className="px-3 py-2 text-right">Net</th>
            <th className="px-3 py-2 text-center">Counts</th>
          </tr>
        </thead>
        <tbody>
          {days.map((day) => (
            <Fragment key={day.date}>
              <tr className="border-t-2 border-zinc-300 bg-zinc-100">
                <td colSpan={6} className="px-3 py-1.5 text-xs font-semibold text-zinc-700">{formatDay(day.date)}</td>
              </tr>
              {day.appointments.map((g) => (
                <Fragment key={g.key}>
                  <tr className="border-t border-zinc-200 bg-zinc-50">
                    <td colSpan={6} className="px-3 py-1.5 pl-4 text-xs">
                      <span className="font-medium">
                        <AppointmentCell date={g.date} time={g.time} bookingId={g.bookingId} label={g.time ?? 'Appointment'} />
                      </span>
                      {g.customer && <span className="text-zinc-500"> · {g.customer}</span>}
                      <span className="text-zinc-400"> · {g.lines.length} {g.lines.length === 1 ? 'service' : 'services'}</span>
                    </td>
                  </tr>
                  {g.lines.map((l, i) => (
                    <tr key={i} className="hover:bg-zinc-50">
                      <td className="px-3 py-2 pl-8">
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
                </Fragment>
              ))}
            </Fragment>
          ))}
          {lines.length === 0 && (
            <tr><td colSpan={6} className="px-3 py-4 text-center text-zinc-400">No services this period.</td></tr>
          )}
        </tbody>
        <tfoot className="border-t border-zinc-200 bg-zinc-50 text-xs">
          <tr>
            <td className="px-3 py-2 font-medium" colSpan={2}>
              Counted: {settlement.countedServices} · paid at base {Math.round(baseRate * 100)}%
              {tierApplied && (settlement.half === 'FIRST'
                ? ' · 50/50 month (5% added at month close)'
                : ' · 50/50 month (incl. bonus)')}
            </td>
            <td className="px-3 py-2 text-right text-zinc-500">card {usd(settlement.cardRevenue)}</td>
            <td className="px-3 py-2 text-right text-zinc-500" colSpan={2}>
              tips {usd(settlement.tipsAfterFee)}{settlement.tierBonus > 0 && ` · bonus ${usd(settlement.tierBonus)}`}
            </td>
            <td className="px-3 py-2 text-right font-semibold">→ {usd(settlement.zelleToProvider)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
