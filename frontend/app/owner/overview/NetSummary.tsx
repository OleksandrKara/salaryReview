import Link from 'next/link';
import type { MonthSummary, OwnerOverviewData } from '../../lib/types';
import { InfoTip } from '../../components/InfoTip';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

type SumKey =
  | 'grossRevenue' | 'cardRevenue' | 'cashRevenue'
  | 'payrollCost' | 'cashProviderCompensation' | 'expenseTotal' | 'cashBusinessExpenseTotal' | 'managerLaborCost'
  | 'netRevenue' | 'personalBankTotal' | 'ownerDrawsTotal' | 'profitAfterPersonal';

function sum(months: MonthSummary[], key: SumKey): number {
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
 * top-line gross figure.
 *
 * Structured per the P&L redesign: Revenue (Card/Cash/Gross), Business Expenses (Provider
 * Compensation — Card/Cash, Bank Business Expenses, Other Cash Business Expenses, Manager Time),
 * Net Profit, then Personal/Owner (Personal Bank Transactions, Owner Draws, Profit Remaining After
 * Personal). Provider compensation is sourced from the same engine as the Salary/Commission Report
 * (see OwnerOverviewService.providerCompensationForMonth) — one source of truth, never duplicated
 * here. Personal spend and owner draws are reported for visibility only; neither reduces Net
 * Profit itself. */
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
  const totalCard    = sum(netMonths, 'cardRevenue');
  const totalCash    = sum(netMonths, 'cashRevenue');
  const totalPayrollCard = sum(netMonths, 'payrollCost');
  const totalPayrollCash = sum(netMonths, 'cashProviderCompensation');
  const totalManagerLabor = sum(netMonths, 'managerLaborCost');
  const totalExpense = sum(netMonths, 'expenseTotal');
  const totalCashExpense = sum(netMonths, 'cashBusinessExpenseTotal');
  const totalNet     = sum(netMonths, 'netRevenue');
  const totalPersonal = sum(netMonths, 'personalBankTotal');
  const totalOwnerDraws = sum(netMonths, 'ownerDrawsTotal');
  const totalProfitAfterPersonal = sum(netMonths, 'profitAfterPersonal');
  const netMargin = totalGross > 0 ? ((totalNet / totalGross) * 100).toFixed(1) + '%' : null;
  const netGrowth = smoothedNetGrowth(active);

  const n = netMonths.length;
  const periodLabel =
    n === 1
      ? `${netMonths[0].label} ${netMonths[0].year}`
      : `${netMonths[0].label} ${netMonths[0].year} – ${netMonths[n - 1].label} ${netMonths[n - 1].year}`;
  const uncoveredCount = netMonths.filter((m) => !m.statementCovered).length;

  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200 sm:p-6">
      <p className="text-xs text-zinc-400">{periodLabel}</p>

      <div className="mt-1 flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <div>
          <span className="text-xs font-medium text-zinc-400">Gross Revenue</span>
          <div className="text-xl font-semibold tabular-nums text-zinc-700">{usd(totalGross)}</div>
        </div>
        <div>
          <span className="text-xs font-medium text-emerald-600">Net Profit</span>
          <div data-testid="net-summary-net" className="text-3xl font-semibold tabular-nums text-emerald-700 sm:text-4xl">
            {usd(totalNet)}
          </div>
        </div>
      </div>

      {uncoveredCount > 0 && (
        <div
          data-testid="net-summary-estimate-banner"
          className="mt-2 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-amber-200"
        >
          Bank business expenses and manager time for {uncoveredCount} of {netMonths.length} month{netMonths.length === 1 ? '' : 's'} shown
          {' '}are estimates until that month&apos;s bank statement is reconciled. Provider compensation always comes
          {' '}from the Salary/Commission Report, regardless of reconciliation status.{' '}
          <Link href="/owner/overview/expenses/history" className="font-medium underline">
            Review imports →
          </Link>
        </div>
      )}

      {netGrowth && (
        <div data-testid="net-summary-growth" className="mt-2.5 flex flex-wrap items-center gap-2 sm:gap-3">
          <span
            className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-sm font-semibold ring-1 ${
              netGrowth.positive ? 'bg-green-50 text-green-700 ring-green-200' : 'bg-rose-50 text-rose-600 ring-rose-200'
            }`}
          >
            <span className="text-xs font-medium opacity-70">Net</span> {netGrowth.label}
            <InfoTip
              text={`Net growth compares the average of the first ${netGrowth.w} month${netGrowth.w > 1 ? 's' : ''} of the selected range with the average of the last ${netGrowth.w} — so one unusually high or low month doesn't swing it. Net Profit = gross revenue − provider compensation (card + cash) − business expenses (bank + cash) − manager time.`}
              label="How Net growth is calculated"
            />
          </span>
        </div>
      )}

      <p className="mt-4 border-t border-zinc-100 pt-3 text-xs font-semibold uppercase tracking-wide text-zinc-400">Revenue</p>
      <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
        <div><Kpi label="Card" value={usd(totalCard)} /></div>
        <div><Kpi label="Cash" value={usd(totalCash)} /></div>
        <div><Kpi label="Gross" value={usd(totalGross)} /></div>
      </div>

      <p className="mt-4 border-t border-zinc-100 pt-3 text-xs font-semibold uppercase tracking-wide text-zinc-400">Business Expenses</p>
      <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3 lg:grid-cols-5">
        <div data-testid="net-summary-payroll"><Kpi
          label="Provider Comp — Card"
          value={`− ${usd(totalPayrollCard)}`}
          tip="Sourced from the same Salary/Commission Report engine (SettlementPreviewService) — never a second calculation."
        /></div>
        <div data-testid="net-summary-cash-comp"><Kpi
          label="Provider Comp — Cash"
          value={`− ${usd(totalPayrollCash)}`}
          tip="The provider's share of cash revenue, from the same Salary/Commission Report engine — never a fake bank transaction."
        /></div>
        <div data-testid="net-summary-expenses"><Kpi label="Bank Business Expenses" value={`− ${usd(totalExpense)}`} /></div>
        <div data-testid="net-summary-cash-expenses"><Kpi label="Other Cash Business Expenses" value={`− ${usd(totalCashExpense)}`} /></div>
        <div data-testid="net-summary-manager-labor"><Kpi label="Manager Time" value={`− ${usd(totalManagerLabor)}`} /></div>
      </div>

      <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-zinc-100 pt-3">
        <span className="text-sm font-semibold text-zinc-700">Net Profit</span>
        <span className="text-lg font-semibold tabular-nums text-emerald-700">{usd(totalNet)}</span>
      </div>

      <p className="mt-4 border-t border-zinc-100 pt-3 text-xs font-semibold uppercase tracking-wide text-zinc-400">Personal / Owner</p>
      <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
        <div><Kpi
          label="Personal Bank Transactions"
          value={usd(totalPersonal)}
          tip="Categorized bank transactions in a personal-flagged category — reported for visibility only, never subtracted from Net Profit."
        /></div>
        <div><Kpi
          label="Owner Draws"
          value={usd(totalOwnerDraws)}
          tip="Bank transactions excluded as owner contribution or cash withdrawal — reported for visibility only, never subtracted from Net Profit."
        /></div>
        <div><Kpi
          label="Profit Remaining After Personal"
          value={usd(totalProfitAfterPersonal)}
          sub="Net Profit − personal spend − owner draws"
        /></div>
      </div>

      <p className="mt-4 border-t border-zinc-100 pt-3 text-xs font-semibold uppercase tracking-wide text-zinc-400">
        Cash Position
        <InfoTip
          text="Cash flows for the period only — not a running cash-on-hand balance. This schema has no source for physical cash on hand (cash that's never deposited never touches the bank), so this deliberately shows only what can be calculated reliably: revenue in, the provider's share, and cash-paid business expenses."
          label="About Cash Position"
        />
      </p>
      <div className="mt-2 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
        <div><Kpi label="Cash Revenue" value={usd(totalCash)} /></div>
        <div><Kpi label="Provider Cash Share" value={`− ${usd(totalPayrollCash)}`} /></div>
        <div><Kpi label="Cash Business Expenses" value={`− ${usd(totalCashExpense)}`} /></div>
        <div><Kpi
          label="Salon's Cash Retained"
          value={usd(totalCash - totalPayrollCash - totalCashExpense)}
          sub="Cash revenue − provider share − cash expenses"
        /></div>
      </div>

      <div className="mt-4 border-t border-zinc-100 pt-4">
        <div data-testid="net-summary-margin"><Kpi
          label="Net margin"
          value={netMargin ?? '—'}
          sub="of gross revenue"
          tip="Net Profit ÷ gross revenue — the share of every dollar collected that's actually left after provider compensation and business expenses."
        /></div>
      </div>
    </div>
  );
}
