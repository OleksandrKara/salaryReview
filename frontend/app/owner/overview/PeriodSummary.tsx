import type { MonthSummary, OwnerOverviewData, YearTotals } from '../../lib/types';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function sum(months: MonthSummary[], key: keyof MonthSummary): number {
  return months.reduce((acc, m) => {
    const v = m[key];
    return acc + (typeof v === 'number' ? v : 0);
  }, 0);
}

function weightedPayrollPct(months: MonthSummary[]): string | null {
  let gross = 0, payroll = 0;
  for (const m of months) {
    if (m.grossRevenue && m.payrollCost) { gross += m.grossRevenue; payroll += m.payrollCost; }
  }
  return gross > 0 ? ((payroll / gross) * 100).toFixed(1) + '%' : null;
}

function overallDelta(current: number, prevYear: YearTotals | null) {
  const prior = prevYear?.totalGross ?? 0;
  if (!prior) return null;
  const d = ((current - prior) / prior) * 100;
  return { d, positive: d >= 0, label: `${d >= 0 ? '↑ +' : '↓ '}${Math.abs(d).toFixed(1)}%` };
}

function Kpi({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div>
      <p className="text-xs text-zinc-400">{label}</p>
      <p className="mt-0.5 text-lg font-semibold tabular-nums text-zinc-800">{value}</p>
      {sub && <p className="text-xs text-zinc-400">{sub}</p>}
    </div>
  );
}

export default function PeriodSummary({ data }: { data: OwnerOverviewData }) {
  const active = data.months.filter((m) => m.grossRevenue != null);
  if (active.length === 0) return null;

  const totalGross = sum(active, 'grossRevenue');
  const totalCard  = sum(active, 'cardRevenue');
  const totalCash  = sum(active, 'cashRevenue');
  const totalTips  = sum(active, 'tips');
  const totalSvc   = sum(active, 'procedures');
  const avgPerAppt = totalSvc > 0 ? totalGross / totalSvc : null;
  const payrollPct = weightedPayrollPct(active);
  const delta      = overallDelta(totalGross, data.prevYear);

  const n = active.length;
  const periodLabel =
    n === 1
      ? `${active[0].label} ${active[0].year}`
      : `${active[0].label} ${active[0].year} – ${active[n - 1].label} ${active[n - 1].year}`;

  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200 sm:p-6">
      <p className="text-xs text-zinc-400">{periodLabel}</p>

      <div className="mt-1">
        <span data-testid="period-summary-gross" className="text-3xl font-semibold tabular-nums text-zinc-900 sm:text-4xl">
          {usd(totalGross)}
        </span>
      </div>

      {delta && (
        <div data-testid="period-summary-growth" className="mt-2.5 flex flex-wrap items-center gap-3">
          <span className={`inline-flex items-center rounded px-2 py-0.5 text-sm font-semibold ring-1 ${
            delta.positive
              ? 'bg-green-50 text-green-700 ring-green-200'
              : 'bg-rose-50 text-rose-600 ring-rose-200'
          }`}>
            {delta.label}
          </span>
          <span className="text-xs text-zinc-400">
            vs {usd(data.prevYear?.totalGross)} prior year
          </span>
        </div>
      )}

      <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 border-t border-zinc-100 pt-4 sm:grid-cols-4">
        <div data-testid="period-summary-payroll-pct"><Kpi label="Payroll %" value={payrollPct ?? '—'} sub="of gross revenue" /></div>
        <div data-testid="period-summary-avg-appt"><Kpi label="Avg / appt" value={usd(avgPerAppt)} sub={`${totalSvc} services`} /></div>
        <div data-testid="period-summary-card"><Kpi
          label="Card"
          value={usd(totalCard)}
          sub={totalGross > 0 ? `${((totalCard / totalGross) * 100).toFixed(0)}% of gross` : undefined}
        /></div>
        <div data-testid="period-summary-cash"><Kpi
          label="Cash"
          value={usd(totalCash)}
          sub={totalTips > 0 ? `+ ${usd(totalTips)} tips` : undefined}
        /></div>
      </div>
    </div>
  );
}
