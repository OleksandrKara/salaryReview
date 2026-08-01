import type { MonthSummary, OwnerOverviewData } from '../../lib/types';
import { InfoTip } from '../../components/InfoTip';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function sum(months: MonthSummary[], key: 'grossRevenue' | 'payrollCost' | 'expenseTotal' | 'netRevenue'): number {
  return months.reduce((acc, m) => acc + (m[key] ?? 0), 0);
}

// Same endpoint-averaging approach as PeriodSummary's smoothedGrowth, applied to net revenue
// instead of gross — one noisy month at either end of the range shouldn't swing the headline.
function smoothedNetGrowth(months: MonthSummary[]): { d: number; w: number; positive: boolean; label: string } | null {
  const active = months.filter((m) => m.netRevenue != null);
  if (active.length < 2) return null;
  const w = Math.max(1, Math.min(3, Math.floor(active.length / 2)));
  const avg = (win: MonthSummary[]) => {
    let s = 0, c = 0;
    for (const m of win) { if (m.netRevenue != null) { s += m.netRevenue; c += 1; } }
    return c > 0 ? s / c : null;
  };
  const firstAvg = avg(active.slice(0, w));
  const lastAvg = avg(active.slice(active.length - w));
  if (firstAvg == null || lastAvg == null || firstAvg === 0) return null;
  const d = ((lastAvg - firstAvg) / firstAvg) * 100;
  return { d, w, positive: d >= 0, label: `${d >= 0 ? '↑ +' : '↓ '}${Math.abs(d).toFixed(1)}%` };
}

function Kpi({ label, value, sub, tip }: { label: string; value: string; sub?: string; tip?: string }) {
  return (
    <div>
      <p className="text-xs text-zinc-400">{label}{tip && <InfoTip text={tip} label={`What is ${label}`} />}</p>
      <p className="mt-0.5 text-lg font-semibold tabular-nums text-zinc-800">{value}</p>
      {sub && <p className="text-xs text-zinc-400">{sub}</p>}
    </div>
  );
}

/** Net-revenue hero for the Net tab — mirrors PeriodSummary's visual weight (a big headline number
 * plus a KPI row), but for what's actually left after payroll and business expenses instead of the
 * top-line gross figure. */
export default function NetSummary({ data }: { data: OwnerOverviewData }) {
  const active = data.months.filter((m) => m.grossRevenue != null);
  if (active.length === 0) return null;
  const netMonths = active.filter((m) => m.netRevenue != null);
  if (netMonths.length === 0) {
    return (
      <div className="rounded-lg p-4 ring-1 ring-zinc-200 sm:p-6">
        <p className="text-sm text-zinc-500">Net revenue isn&apos;t available for the selected range yet.</p>
      </div>
    );
  }

  const totalGross   = sum(netMonths, 'grossRevenue');
  const totalPayroll = sum(netMonths, 'payrollCost');
  const totalExpense = sum(netMonths, 'expenseTotal');
  const totalNet     = sum(netMonths, 'netRevenue');
  const netMargin = totalGross > 0 ? ((totalNet / totalGross) * 100).toFixed(1) + '%' : null;
  const netGrowth = smoothedNetGrowth(active);

  const n = netMonths.length;
  const periodLabel =
    n === 1
      ? `${netMonths[0].label} ${netMonths[0].year}`
      : `${netMonths[0].label} ${netMonths[0].year} – ${netMonths[n - 1].label} ${netMonths[n - 1].year}`;

  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200 sm:p-6">
      <p className="text-xs text-zinc-400">{periodLabel}</p>

      <div className="mt-1">
        <span data-testid="net-summary-net" className="text-3xl font-semibold tabular-nums text-emerald-700 sm:text-4xl">
          {usd(totalNet)}
        </span>
      </div>

      {netGrowth && (
        <div data-testid="net-summary-growth" className="mt-2.5 flex flex-wrap items-center gap-2 sm:gap-3">
          <span
            className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-sm font-semibold ring-1 ${
              netGrowth.positive ? 'bg-green-50 text-green-700 ring-green-200' : 'bg-rose-50 text-rose-600 ring-rose-200'
            }`}
          >
            <span className="text-xs font-medium opacity-70">Net</span> {netGrowth.label}
            <InfoTip
              text={`Net growth compares the average of the first ${netGrowth.w} month${netGrowth.w > 1 ? 's' : ''} of the selected range with the average of the last ${netGrowth.w} — so one unusually high or low month doesn't swing it. Net = gross revenue − payroll − business expenses.`}
              label="How Net growth is calculated"
            />
          </span>
        </div>
      )}

      <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 border-t border-zinc-100 pt-4 sm:grid-cols-4">
        <div data-testid="net-summary-gross"><Kpi label="Gross" value={usd(totalGross)} /></div>
        <div data-testid="net-summary-payroll"><Kpi label="Payroll" value={`− ${usd(totalPayroll)}`} /></div>
        <div data-testid="net-summary-expenses"><Kpi label="Expenses" value={`− ${usd(totalExpense)}`} /></div>
        <div data-testid="net-summary-margin"><Kpi
          label="Net margin"
          value={netMargin ?? '—'}
          sub="of gross revenue"
          tip="Net revenue ÷ gross revenue — the share of every dollar collected that's actually left after payroll and business expenses (materials, rent, utilities, etc.)."
        /></div>
      </div>
    </div>
  );
}
