import { Fragment } from 'react';
import type { AttributedService, HalfSettlement } from '../lib/types';
import { AppointmentCell } from './AppointmentCell';
import { InfoTip } from './InfoTip';
import { groupByDay, formatDay } from '../lib/grouping';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function ChannelTag({ channel }: { channel: string }) {
  const map: Record<string, string> = {
    CARD: 'bg-blue-50 text-blue-700 ring-blue-200',
    CASH: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    'CASH-NOTE': 'bg-amber-50 text-amber-700 ring-amber-200',
    PREPAID: 'bg-violet-50 text-violet-700 ring-violet-200',
    COMP: 'bg-rose-50 text-rose-700 ring-rose-200',
    REDO: 'bg-orange-50 text-orange-700 ring-orange-200',
    MANUAL: 'bg-sky-50 text-sky-700 ring-sky-200',
    NOSHOW: 'bg-yellow-50 text-yellow-800 ring-yellow-300',
  };
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ring-1 ${map[channel] ?? 'bg-zinc-100 text-zinc-600 ring-zinc-300'}`}>{channel}</span>;
}

function round2(n: number): number {
  return Math.round((n + Number.EPSILON) * 100) / 100;
}

/** This line's split of what was actually collected (net): the provider's cut is the half's own
 * applied commission rate against the menu price (gross) — the same rate the half's own zelle
 * total is built from (HalfSettlement#appliedRate) — and the salon keeps whatever's left of net
 * after that, mirroring the backend engine's own cashToSalon formula (collected − gross × rate)
 * generalized to every channel, card included. A discount (gross > net) isn't credited to either
 * side — it's money the customer never paid — so providerShare + salonShare always equals net, by
 * construction, never gross.
 *
 * <p>An estimate, not the ledger: a first-half line in a month that goes on to qualify for the
 * tier is shown at base rate here (same as the half's own provisional zelle total) — the retroactive
 * uplift is a month-wide lump sum only known at month close, not something that was ever earned by
 * one specific service, so there's nothing more precise to attribute it to per line. */
function payoutSplit(line: AttributedService, appliedRate: number): { provider: number; salon: number } {
  const provider = round2(line.gross * appliedRate);
  return { provider, salon: round2(line.net - provider) };
}

function countsLabel(units: number): string {
  if (units < 0) return 'removed'; // a redo line that takes a counted service away from the original provider
  if (units === 0) return 'add-on';
  return units === 1 ? '✓ counts' : `✓ counts ×${units}`;
}

// Tip for a transaction (an appointment group's lines) — the order tip, attributed to this provider.
const sumTip = (ls: AttributedService[]) => ls.reduce((s, l) => s + l.tip, 0);
const sumGross = (ls: AttributedService[]) => ls.reduce((s, l) => s + l.gross, 0);
const sumDiscount = (ls: AttributedService[]) => ls.reduce((s, l) => s + l.discount, 0);

// Service lines for one half, grouped by day → appointment. A wide table on tablet/desktop; on a
// phone, stacked appointment cards (no horizontal scrolling). Shared by the owner drill-down and /me.
export default function ServiceLinesTable({
  lines,
  settlement,
  tierApplied,
  baseRate,
  showPayoutSplit = false,
}: {
  lines: AttributedService[];
  settlement: HalfSettlement;
  tierApplied: boolean; // whether the month qualifies for 50/50 (earned or granted)
  baseRate: number; // the base rate every half's card is actually paid at (e.g. 0.45)
  /** Owner-only per-service "who keeps what" column (see payoutSplit's own doc) — never shown on
   * the provider's own /me self-view, which would otherwise expose the salon's per-line margin. */
  showPayoutSplit?: boolean;
}) {
  const days = groupByDay(lines);
  const colCount = showPayoutSplit ? 8 : 7;
  const tierNote = tierApplied
    ? (settlement.half === 'FIRST' ? ' · 50/50 month (5% added at month close)' : ' · 50/50 month (incl. bonus)')
    : '';
  const summary = `Counted: ${settlement.countedServices} · paid at base ${Math.round(baseRate * 100)}%${tierNote}`;
  const tipTotal = sumTip(lines); // gross tip total — the sum of the Tip column
  const discountTotal = lines.reduce((s, l) => s + l.discount, 0);
  const grossTotal = sumGross(lines); // the actual sum of the Gross column (all channels, not just card)
  // Gross split by transaction type, for the totals-row tooltip (so the gross total is traceable).
  const grossByChannel = lines.reduce<Record<string, number>>((m, l) => { m[l.channel] = (m[l.channel] ?? 0) + l.gross; return m; }, {});
  const channelLabel: Record<string, string> = {
    CARD: 'Card', CASH: 'Cash', 'CASH-NOTE': 'Cash note', PREPAID: 'Prepaid',
    COMP: 'Comp', REDO: 'Redo', MANUAL: 'Manual', NOSHOW: 'No-show fee',
  };
  const grossBreakdown = Object.entries(grossByChannel)
    .filter(([, v]) => Math.abs(v) > 0.005)
    .map(([k, v]) => `${channelLabel[k] ?? k} ${usd(v)}`).join(' · ');
  const providerTotal = showPayoutSplit
    ? round2(lines.reduce((s, l) => s + payoutSplit(l, settlement.appliedRate).provider, 0)) : 0;
  const salonTotal = showPayoutSplit
    ? round2(lines.reduce((s, l) => s + payoutSplit(l, settlement.appliedRate).salon, 0)) : 0;

  return (
    <>
      {/* Phone: stacked appointment cards. */}
      <div className="flex flex-col gap-4 sm:hidden">
        {days.map((day) => (
          <div key={day.date}>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-zinc-500">{formatDay(day.date)}</div>
            <div className="flex flex-col gap-2">
              {day.appointments.map((g) => (
                <div key={g.key} className="overflow-hidden rounded-lg ring-1 ring-zinc-200">
                  <div className="flex flex-wrap items-baseline gap-x-1.5 bg-zinc-50 px-3 py-1.5 text-xs">
                    <span className="font-medium">
                      <AppointmentCell date={g.date} time={g.time} bookingId={g.bookingId} label={g.time ?? 'Appointment'} />
                    </span>
                    {g.customer && <span className="text-zinc-500">· {g.customer}</span>}
                    <span className="text-zinc-400">· {g.lines.length} {g.lines.length === 1 ? 'service' : 'services'}</span>
                    <span className="text-zinc-500">· {usd(sumGross(g.lines))}</span>
                    {sumDiscount(g.lines) > 0 && <span className="text-emerald-700">· −{usd(sumDiscount(g.lines))} disc</span>}
                    {sumTip(g.lines) > 0 && <span className="text-zinc-500">· tip {usd(sumTip(g.lines))}</span>}
                  </div>
                  <div className="divide-y divide-zinc-100">
                    {g.lines.map((l, i) => (
                      <div key={i} className="flex items-start justify-between gap-3 px-3 py-2 text-sm">
                        <div className="min-w-0">
                          <div className="break-words">{l.service}</div>
                          <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-zinc-400">
                            <ChannelTag channel={l.channel} />
                            {l.prepaid && <span className="rounded bg-violet-50 px-1 py-0.5 font-medium text-violet-700 ring-1 ring-violet-200">prepaid</span>}
                            <span>{countsLabel(l.countedUnits)}</span>
                          </div>
                        </div>
                        <div className="shrink-0 text-right tabular-nums">
                          <div>{usd(l.gross)}</div>
                          {l.discount > 0 && <div className="text-[11px] text-emerald-700">−{usd(l.discount)} disc</div>}
                          {l.tip > 0 && <div className="text-[11px] text-zinc-500">+{usd(l.tip)} tip</div>}
                          {showPayoutSplit && (() => {
                            const split = payoutSplit(l, settlement.appliedRate);
                            return (
                              <div className="mt-1 flex flex-col gap-0.5 border-t border-dashed border-zinc-200 pt-1 text-[11px]">
                                <span className="text-blue-700">{usd(split.provider)} provider</span>
                                <span className="text-zinc-400">{usd(split.salon)} salon</span>
                              </div>
                            );
                          })()}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
        {lines.length === 0 && <p className="py-4 text-center text-zinc-400">No services this period.</p>}
        <div className="space-y-1 rounded-lg bg-zinc-50 px-3 py-2 text-xs ring-1 ring-zinc-200">
          <div className="font-medium">{summary}</div>
          <div className="flex items-baseline justify-between gap-2 text-zinc-500">
            <span>
              gross {usd(grossTotal)}{Object.keys(grossByChannel).length > 1 && <InfoTip text={grossBreakdown} label="Gross by transaction type" />} · tips {usd(settlement.tipsAfterFee)}{settlement.tierBonus > 0 && ` · bonus ${usd(settlement.tierBonus)}`}
            </span>
            <span className="font-semibold text-zinc-900">→ {usd(settlement.zelleToProvider)}</span>
          </div>
        </div>
      </div>

      {/* Tablet / desktop: the full table. */}
      <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2">Channel</th>
              <th className="px-3 py-2 text-right">Gross</th>
              <th className="px-3 py-2 text-right">Discount</th>
              <th className="px-3 py-2 text-right">Net</th>
              <th className="px-3 py-2 text-right">Tip</th>
              {showPayoutSplit && (
                <th className="px-3 py-2 text-right">
                  <span className="inline-flex items-center gap-1">
                    Payout
                    <InfoTip
                      label="How the payout column is split"
                      text="Provider's cut is this half's applied commission rate against the menu price; the salon keeps whatever's left of what was actually collected. A discount isn't credited to either side. First-half lines show base rate — a monthly tier bonus, if qualified, is a lump sum only known at month close, not attributable to one service."
                    />
                  </span>
                </th>
              )}
              <th className="px-3 py-2 text-center">Counts</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <Fragment key={day.date}>
                <tr className="border-t-2 border-zinc-300 bg-zinc-100">
                  <td colSpan={colCount} className="px-3 py-1.5 text-xs font-semibold text-zinc-700">{formatDay(day.date)}</td>
                </tr>
                {day.appointments.map((g) => (
                  <Fragment key={g.key}>
                    <tr className="border-t border-zinc-200 bg-zinc-50">
                      <td colSpan={colCount} className="px-3 py-1.5 pl-4 text-xs">
                        <span className="font-medium">
                          <AppointmentCell date={g.date} time={g.time} bookingId={g.bookingId} label={g.time ?? 'Appointment'} />
                        </span>
                        {g.customer && <span className="text-zinc-500"> · {g.customer}</span>}
                        <span className="text-zinc-400"> · {g.lines.length} {g.lines.length === 1 ? 'service' : 'services'}</span>
                        <span className="text-zinc-500"> · {usd(sumGross(g.lines))}</span>
                        {sumDiscount(g.lines) > 0 && <span className="text-emerald-700"> · −{usd(sumDiscount(g.lines))} disc</span>}
                        {sumTip(g.lines) > 0 && <span className="text-zinc-500"> · tip {usd(sumTip(g.lines))}</span>}
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
                        <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{l.tip > 0 ? usd(l.tip) : '—'}</td>
                        {showPayoutSplit && (() => {
                          const split = payoutSplit(l, settlement.appliedRate);
                          return (
                            <td className="px-3 py-2 text-right tabular-nums">
                              <div className="text-blue-700">{usd(split.provider)}</div>
                              <div className="text-[11px] text-zinc-400">{usd(split.salon)} salon</div>
                            </td>
                          );
                        })()}
                        <td className="px-3 py-2 text-center">
                          {l.countedUnits < 0 ? <span className="text-orange-600">removed</span>
                            : l.countedUnits === 0 ? <span className="text-zinc-300">—</span>
                            : l.countedUnits === 1 ? '✓' : `✓ ×${l.countedUnits}`}
                        </td>
                      </tr>
                    ))}
                  </Fragment>
                ))}
              </Fragment>
            ))}
            {lines.length === 0 && (
              <tr><td colSpan={colCount} className="px-3 py-4 text-center text-zinc-400">No services this period.</td></tr>
            )}
          </tbody>
          <tfoot className="border-t border-zinc-200 bg-zinc-50 text-xs">
            <tr>
              <td className="px-3 py-2 font-medium" colSpan={2}>Totals</td>
              <td className="px-3 py-2 text-right tabular-nums font-medium">
                <span className="inline-flex items-center justify-end">
                  {usd(grossTotal)}
                  {Object.keys(grossByChannel).length > 1 && <InfoTip text={grossBreakdown} label="Gross by transaction type" />}
                </span>
              </td>
              <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{discountTotal > 0 ? `−${usd(discountTotal)}` : '—'}</td>
              <td className="px-3 py-2" />
              <td className="px-3 py-2 text-right tabular-nums font-medium">{usd(tipTotal)}</td>
              {showPayoutSplit && (
                <td className="px-3 py-2 text-right tabular-nums font-medium">
                  <div className="text-blue-700">{usd(providerTotal)}</div>
                  <div className="text-[11px] font-normal text-zinc-400">{usd(salonTotal)} salon</div>
                </td>
              )}
              <td className="px-3 py-2 text-center font-medium">{settlement.countedServices}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </>
  );
}
