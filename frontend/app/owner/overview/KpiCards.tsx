'use client';

import type { MonthSummary, OwnerOverviewData } from '../../lib/types';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function pct(n: number | null | undefined) {
  return n == null ? '—' : `${n}%`;
}

function DeltaBadge({ current, prior }: { current: number | null; prior: number | null }) {
  if (current == null || prior == null || prior === 0) return null;
  const d = ((current - prior) / prior) * 100;
  const pos = d >= 0;
  return (
    <span className={`text-xs font-medium ${pos ? 'text-green-600' : 'text-red-500'}`}>
      {pos ? '↑' : '↓'} {Math.abs(d).toFixed(1)}%
    </span>
  );
}

function KpiTile({
  label,
  value,
  sub,
  accent,
}: {
  label: string;
  value: string;
  sub?: React.ReactNode;
  accent?: boolean;
}) {
  return (
    <div className={`rounded-xl p-4 ${accent ? 'bg-zinc-800 text-white' : 'bg-zinc-50 ring-1 ring-zinc-200'}`}>
      <div className={`text-xs font-medium ${accent ? 'text-zinc-400' : 'text-zinc-500'}`}>{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${accent ? 'text-white' : 'text-zinc-800'}`}>{value}</div>
      {sub && <div className="mt-1">{sub}</div>}
    </div>
  );
}

function SmallKpi({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-zinc-50 p-3 ring-1 ring-zinc-200">
      <div className="text-xs font-medium text-zinc-500">{label}</div>
      <div className="mt-0.5 text-lg font-semibold tabular-nums text-zinc-800">{value}</div>
    </div>
  );
}

// Compute period-level totals from all months with data
function periodTotals(months: MonthSummary[]) {
  let gross = 0, card = 0, cash = 0, tips = 0, procedures = 0, payroll = 0;
  let payrollMonths = 0;
  for (const m of months) {
    if (m.grossRevenue == null) continue;
    gross      += m.grossRevenue;
    card       += m.cardRevenue ?? 0;
    cash       += m.cashRevenue ?? 0;
    tips       += m.tips ?? 0;
    procedures += m.procedures;
    if (m.payrollCost != null) { payroll += m.payrollCost; payrollMonths++; }
  }
  const payrollPct = gross > 0 ? ((payroll / gross) * 100).toFixed(1) : null;
  const avg = procedures > 0 ? gross / procedures : null;
  return { gross, card, cash, tips, procedures, payrollPct, avg };
}

export default function KpiCards({
  data,
  selectedIndex,
}: {
  data: OwnerOverviewData;
  selectedIndex: number;
}) {
  const totals = periodTotals(data.months);
  const selected = data.months[selectedIndex] ?? null;
  const prevMonth = selectedIndex > 0 ? data.months[selectedIndex - 1] : null;
  const prevYearGross = data.prevYear?.totalGross ?? null;

  const hasData = totals.gross > 0;

  return (
    <div className="space-y-4">
      {/* Period summary */}
      {hasData && (
        <div className="space-y-2">
          <p className="text-xs font-medium text-zinc-400 uppercase tracking-wide">Period total</p>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <KpiTile
              label="Gross revenue"
              value={usd(totals.gross)}
              sub={
                <DeltaBadge
                  current={totals.gross}
                  prior={prevYearGross}
                />
              }
              accent
            />
            <KpiTile label="Payroll %" value={totals.payrollPct ? `${totals.payrollPct}%` : '—'} />
            <KpiTile label="Card revenue" value={usd(totals.card)} />
            <KpiTile label="Cash revenue" value={usd(totals.cash)} />
          </div>
          <div className="grid grid-cols-3 gap-2">
            <SmallKpi label="Tips" value={usd(totals.tips)} />
            <SmallKpi label="Avg / appt" value={usd(totals.avg)} />
            <SmallKpi label="Services" value={totals.procedures > 0 ? String(totals.procedures) : '—'} />
          </div>
        </div>
      )}

      {/* Selected month detail */}
      {selected && selected.grossRevenue != null && (
        <div className="space-y-2">
          <p className="text-xs font-medium text-zinc-400 uppercase tracking-wide">
            {selected.label} {selected.year}
            {!selected.finalized && (
              <span className="ml-1.5 rounded bg-blue-50 px-1.5 py-0.5 text-[10px] font-normal text-blue-500 ring-1 ring-blue-200">live</span>
            )}
          </p>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <div className="rounded-xl bg-zinc-50 p-4 ring-1 ring-zinc-200">
              <div className="text-xs font-medium text-zinc-500">Gross</div>
              <div className="mt-1 text-xl font-semibold tabular-nums text-zinc-800">{usd(selected.grossRevenue)}</div>
              <div className="mt-1 flex items-center gap-2">
                <DeltaBadge current={selected.grossRevenue} prior={prevMonth?.grossRevenue ?? null} />
                {prevMonth?.grossRevenue != null && (
                  <span className="text-[10px] text-zinc-400">vs {prevMonth.label}</span>
                )}
              </div>
            </div>
            <SmallKpi label="Card" value={usd(selected.cardRevenue)} />
            <SmallKpi label="Cash" value={usd(selected.cashRevenue)} />
            <SmallKpi label="Payroll %" value={pct(selected.payrollPct)} />
          </div>
          <div className="grid grid-cols-3 gap-2">
            <SmallKpi label="Tips" value={usd(selected.tips)} />
            <SmallKpi label="Avg / appt" value={usd(selected.avgPerAppt)} />
            <SmallKpi label="Services" value={selected.procedures > 0 ? String(selected.procedures) : '—'} />
          </div>
        </div>
      )}

      {!hasData && (
        <p className="py-6 text-center text-sm text-zinc-400">
          No data for this period. Try adjusting the date range.
        </p>
      )}
    </div>
  );
}
