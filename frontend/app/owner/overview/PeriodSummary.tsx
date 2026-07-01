import type { MonthSummary, OwnerOverviewData } from '../../lib/types';
import { InfoTip } from '../../components/InfoTip';

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

// Growth across the selected range, comparing the AVERAGE of the first N months to the average of the
// last N months (N up to 3, never overlapping). Endpoint-averaging is the fix for the old first-vs-last
// month formula, which swung wildly whenever one endpoint month happened to be high or low — e.g. adding
// a single softer month could drop the headline growth from +70% to +50%. Averaging a window at each end
// tracks the real trend instead of one month's noise.
function smoothedGrowth(active: MonthSummary[], key: 'grossRevenue' | 'procedures') {
  if (active.length < 2) return null;
  const w = Math.max(1, Math.min(3, Math.floor(active.length / 2)));
  const avg = (win: MonthSummary[]) => {
    let s = 0, c = 0;
    for (const m of win) {
      const v = m[key];
      if (typeof v === 'number') { s += v; c += 1; }
    }
    return c > 0 ? s / c : null;
  };
  const firstAvg = avg(active.slice(0, w));
  const lastAvg = avg(active.slice(active.length - w));
  if (firstAvg == null || lastAvg == null || firstAvg === 0) return null;
  const d = ((lastAvg - firstAvg) / firstAvg) * 100;
  return { d, w, positive: d >= 0, label: `${d >= 0 ? '↑ +' : '↓ '}${Math.abs(d).toFixed(1)}%` };
}

type Growth = { d: number; w: number; positive: boolean; label: string };

function GrowthChip({ name, growth, what }: { name: string; growth: Growth | null; what: string }) {
  if (!growth) return null;
  const tip =
    `${name} growth compares the average of the first ${growth.w} month${growth.w > 1 ? 's' : ''} of the ` +
    `selected range with the average of the last ${growth.w} — so one unusually high or low month doesn’t ` +
    `swing it. ${what}`;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-sm font-semibold ring-1 ${
        growth.positive ? 'bg-green-50 text-green-700 ring-green-200' : 'bg-rose-50 text-rose-600 ring-rose-200'
      }`}
    >
      <span className="text-xs font-medium opacity-70">{name}</span> {growth.label}
      <InfoTip text={tip} label={`How ${name} growth is calculated`} />
    </span>
  );
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
  const revenueGrowth = smoothedGrowth(active, 'grossRevenue');
  const servicesGrowth = smoothedGrowth(active, 'procedures');

  const n = active.length;
  const periodLabel =
    n === 1
      ? `${active[0].label} ${active[0].year}`
      : `${active[0].label} ${active[0].year} – ${active[n - 1].label} ${active[n - 1].year}`;
  const endpoints = `${active[0].label} '${String(active[0].year).slice(2)} → ${active[n - 1].label} '${String(active[n - 1].year).slice(2)}`;

  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200 sm:p-6">
      <p className="text-xs text-zinc-400">{periodLabel}</p>

      <div className="mt-1">
        <span data-testid="period-summary-gross" className="text-3xl font-semibold tabular-nums text-zinc-900 sm:text-4xl">
          {usd(totalGross)}
        </span>
      </div>

      {(revenueGrowth || servicesGrowth) && (
        <div data-testid="period-summary-growth" className="mt-2.5 flex flex-wrap items-center gap-2 sm:gap-3">
          <GrowthChip
            name="Revenue"
            growth={revenueGrowth}
            what="Gross revenue, all providers, card + cash."
          />
          <GrowthChip
            name="Services"
            growth={servicesGrowth}
            what="Completed service volume (the count behind ‘Avg / appt’). For distinct-client growth, see the Retention page."
          />
          <span className="text-xs text-zinc-400">{endpoints}</span>
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
