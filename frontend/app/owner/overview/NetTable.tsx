import type { MonthSummary } from '../../lib/types';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function momPct(months: MonthSummary[], i: number, key: 'netRevenue' | 'grossRevenue'): number | null {
  if (i === 0) return null;
  const cur  = months[i][key];
  const prev = months[i - 1][key];
  if (cur == null || prev == null || prev === 0) return null;
  return ((cur - prev) / prev) * 100;
}

function MomBadge({ pct }: { pct: number | null }) {
  if (pct == null) return <span className="text-zinc-300">—</span>;
  const pos = pct >= 0;
  return (
    <span className={`font-semibold tabular-nums ${pos ? 'text-emerald-600' : 'text-rose-500'}`}>
      {pos ? '↑ +' : '↓ '}{Math.abs(pct).toFixed(1)}%
    </span>
  );
}

/** Whether this month's Payroll/Manager time/Expenses are real bank-statement figures or
 * formula/clocked-time estimates — the exact question the owner asked about after the numbers
 * changed source without any visible explanation. Same color convention as `StatusBadge` in
 * `ImportHistoryList.tsx` (emerald = completed/real, amber = still pending/estimated). */
function SourceBadge({ statementCovered }: { statementCovered: boolean }) {
  return statementCovered ? (
    <span
      className="rounded-md bg-emerald-50 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200"
      title="Payroll, manager time, and expenses are real numbers from a completed bank-statement reconciliation for this month."
    >
      Statement
    </span>
  ) : (
    <span
      className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200"
      title="Payroll, manager time, and expenses are estimates (commission formula / clocked time / manual entries) — complete this month's bank-statement reconciliation for real numbers."
    >
      Estimate
    </span>
  );
}

/** Monthly Gross → Payroll → Expenses → Net breakdown — the cost side split out of GrowthTable
 * (see that component's own doc comment) into its own Revenue tab, since "what's the gross number"
 * and "what's actually left after payroll and expenses" are different questions a manager asks at
 * different times, not one row each in an increasingly wide table. Dual-rendered: a stacked card
 * per month below `sm` (Payroll/Manager time/Expenses used to be entirely invisible on mobile —
 * hidden table columns with no fallback), a table at `sm` and up, matching this codebase's
 * established `TransactionRow`/`ContactsTable` convention. Every row/card also carries a
 * Statement/Estimate badge so it's clear at a glance whether that month's cost figures are real
 * bank-statement numbers or estimates, not just an aggregate banner elsewhere on the page. */
export default function NetTable({ months }: { months: MonthSummary[] }) {
  const active = months.filter((m) => m.grossRevenue != null);
  if (active.length === 0) return null;

  const totalGross   = active.reduce((s, m) => s + (m.grossRevenue ?? 0), 0);
  const totalPayroll = active.reduce((s, m) => s + (m.payrollCost ?? 0), 0);
  const totalManagerLabor = active.reduce((s, m) => s + (m.managerLaborCost ?? 0), 0);
  const totalExpense = active.reduce((s, m) => s + (m.expenseTotal ?? 0), 0);
  const netMonths = active.filter((m) => m.netRevenue != null);
  const totalNet = netMonths.length > 0 ? netMonths.reduce((s, m) => s + (m.netRevenue ?? 0), 0) : null;
  const overallMom = netMonths.length > 1
    ? momPct([netMonths[0], netMonths[netMonths.length - 1]], 1, 'netRevenue')
    : null;

  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-2 sm:hidden" data-testid="net-table-mobile">
        {active.map((m, i) => {
          const pct = momPct(active, i, 'netRevenue');
          const isLive = !m.finalized;
          return (
            <div
              key={`${m.year}-${m.month}`}
              data-testid={`net-row-mobile-${m.year}-${m.month}`}
              className="rounded-lg p-3 ring-1 ring-zinc-200"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-sm font-medium text-zinc-800">
                    {m.label} <span className="text-zinc-400">&apos;{String(m.year).slice(2)}</span>
                  </span>
                  {isLive && (
                    <span className="rounded-md bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 ring-1 ring-zinc-300">live</span>
                  )}
                  <SourceBadge statementCovered={m.statementCovered} />
                </div>
                <MomBadge pct={pct} />
              </div>
              <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Gross</span>
                  <span className="tabular-nums text-zinc-600">{usd(m.grossRevenue)}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Net</span>
                  <span className="tabular-nums font-semibold text-emerald-700">{usd(m.netRevenue)}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Payroll</span>
                  <span className="tabular-nums text-zinc-600">{m.payrollCost != null ? `− ${usd(m.payrollCost)}` : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Manager time</span>
                  <span className="tabular-nums text-zinc-600">{m.managerLaborCost != null ? `− ${usd(m.managerLaborCost)}` : '—'}</span>
                </div>
                <div className="col-span-2 flex items-center justify-between">
                  <span className="text-zinc-400">Expenses</span>
                  <span className="tabular-nums text-zinc-600">{m.expenseTotal != null ? `− ${usd(m.expenseTotal)}` : '—'}</span>
                </div>
              </div>
            </div>
          );
        })}
        <div className="rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-300">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-zinc-800">Total</span>
            {overallMom != null && (
              <span className={`text-xs font-semibold ${overallMom >= 0 ? 'text-emerald-600' : 'text-rose-500'}`}>
                {overallMom >= 0 ? '↑ +' : '↓ '}{Math.abs(overallMom).toFixed(1)}%
              </span>
            )}
          </div>
          <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Gross</span>
              <span className="tabular-nums font-medium text-zinc-700">{usd(totalGross)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Net</span>
              <span className="tabular-nums font-semibold text-emerald-700">{usd(totalNet)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Payroll</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalPayroll)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Manager time</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalManagerLabor)}</span>
            </div>
            <div className="col-span-2 flex items-center justify-between">
              <span className="text-zinc-400">Expenses</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalExpense)}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Desktop table */}
      <div data-testid="net-table" className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-zinc-200 bg-zinc-50 text-left text-xs font-semibold uppercase tracking-wide text-zinc-400">
              <th className="px-4 py-3">Month</th>
              <th className="px-4 py-3 text-right">Gross</th>
              <th className="px-4 py-3 text-right">Payroll</th>
              <th className="px-4 py-3 text-right">Manager time</th>
              <th className="px-4 py-3 text-right">Expenses</th>
              <th className="px-4 py-3 text-right">Net</th>
              <th className="px-4 py-3 text-right">Growth</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {active.map((m, i) => {
              const pct = momPct(active, i, 'netRevenue');
              const isLive = !m.finalized;
              return (
                <tr key={`${m.year}-${m.month}`} data-testid={`net-row-${m.year}-${m.month}`} className="hover:bg-zinc-50">
                  <td className="px-4 py-3 font-medium text-zinc-800">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span>{m.label} <span className="text-zinc-400">&apos;{String(m.year).slice(2)}</span></span>
                      {isLive && (
                        <span className="rounded-md bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 ring-1 ring-zinc-300">live</span>
                      )}
                      <SourceBadge statementCovered={m.statementCovered} />
                    </div>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-zinc-500">{usd(m.grossRevenue)}</td>
                  <td className="px-4 py-3 text-right tabular-nums text-zinc-500">
                    {m.payrollCost != null ? `− ${usd(m.payrollCost)}` : '—'}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-zinc-500">
                    {m.managerLaborCost != null ? `− ${usd(m.managerLaborCost)}` : '—'}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-zinc-500">
                    {m.expenseTotal != null ? `− ${usd(m.expenseTotal)}` : '—'}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums font-semibold text-emerald-700">
                    {usd(m.netRevenue)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <MomBadge pct={pct} />
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-zinc-200 bg-zinc-50 font-semibold text-zinc-800">
              <td className="px-4 py-3">Total</td>
              <td className="px-4 py-3 text-right tabular-nums">{usd(totalGross)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalPayroll)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalManagerLabor)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalExpense)}</td>
              <td className="px-4 py-3 text-right tabular-nums text-emerald-700">{usd(totalNet)}</td>
              <td className="px-4 py-3 text-right tabular-nums">
                {overallMom != null && (
                  <span className={overallMom >= 0 ? 'text-emerald-600' : 'text-rose-500'}>
                    {overallMom >= 0 ? '↑ +' : '↓ '}{Math.abs(overallMom).toFixed(1)}%
                  </span>
                )}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </>
  );
}
