import type { MonthSummary } from '../../lib/types';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function momPct(months: MonthSummary[], i: number): number | null {
  if (i === 0) return null;
  const cur  = months[i].grossRevenue;
  const prev = months[i - 1].grossRevenue;
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

export default function GrowthTable({ months }: { months: MonthSummary[] }) {
  const active = months.filter((m) => m.grossRevenue != null);
  if (active.length === 0) return null;

  // Totals row
  const totalGross   = active.reduce((s, m) => s + (m.grossRevenue ?? 0), 0);
  const totalCard    = active.reduce((s, m) => s + (m.cardRevenue ?? 0), 0);
  const totalCash    = active.reduce((s, m) => s + (m.cashRevenue ?? 0), 0);
  const totalSvc     = active.reduce((s, m) => s + m.procedures, 0);
  const totalExpense = active.reduce((s, m) => s + (m.expenseTotal ?? 0), 0);
  const netMonths = active.filter((m) => m.netRevenue != null);
  const totalNet = netMonths.length > 0 ? netMonths.reduce((s, m) => s + (m.netRevenue ?? 0), 0) : null;

  let totalPayroll = 0, totalPayrollGross = 0;
  for (const m of active) {
    if (m.payrollCost != null && m.grossRevenue != null) {
      totalPayroll      += m.payrollCost;
      totalPayrollGross += m.grossRevenue;
    }
  }
  const avgPayrollPct = totalPayrollGross > 0
    ? ((totalPayroll / totalPayrollGross) * 100).toFixed(1) + '%'
    : '—';

  // Overall period growth (last active vs first active)
  const overallMom = active.length > 1
    ? momPct([active[0], active[active.length - 1]], 1)
    : null;

  return (
    <div>
      <div data-testid="growth-table" className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-zinc-200 bg-zinc-50 text-left text-xs font-semibold uppercase tracking-wide text-zinc-400">
              <th className="px-4 py-3">Month</th>
              <th className="px-4 py-3 text-right">Revenue</th>
              <th className="px-4 py-3 text-right">Net</th>
              <th className="px-4 py-3 text-right">Growth</th>
              <th className="hidden px-4 py-3 text-right sm:table-cell">Card</th>
              <th className="hidden px-4 py-3 text-right sm:table-cell">Cash</th>
              <th className="hidden px-4 py-3 text-right sm:table-cell">Payroll</th>
              <th className="hidden px-4 py-3 text-right sm:table-cell">Expenses</th>
              <th className="hidden px-4 py-3 text-right sm:table-cell">Appts</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {active.map((m, i) => {
              const pct    = momPct(active, i);
              const isLive = !m.finalized;
              return (
                <tr
                  key={`${m.year}-${m.month}`}
                  data-testid={`growth-row-${m.year}-${m.month}`}
                  className="hover:bg-zinc-50"
                >
                  <td className="px-4 py-3 font-medium text-zinc-800">
                    <span>{m.label} <span className="text-zinc-400">'{String(m.year).slice(2)}</span></span>
                    {isLive && (
                      <span className="ml-2 rounded-md bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 ring-1 ring-zinc-300">live</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums font-semibold text-zinc-800">
                    {usd(m.grossRevenue)}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums font-semibold text-emerald-700">
                    {usd(m.netRevenue)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <MomBadge pct={pct} />
                  </td>
                  <td className="hidden px-4 py-3 text-right tabular-nums text-zinc-500 sm:table-cell">
                    {usd(m.cardRevenue)}
                  </td>
                  <td className="hidden px-4 py-3 text-right tabular-nums text-zinc-500 sm:table-cell">
                    {usd(m.cashRevenue)}
                  </td>
                  <td className="hidden px-4 py-3 text-right tabular-nums text-zinc-500 sm:table-cell">
                    {m.payrollPct != null ? `${m.payrollPct}%` : '—'}
                  </td>
                  <td className="hidden px-4 py-3 text-right tabular-nums text-zinc-500 sm:table-cell">
                    {usd(m.expenseTotal)}
                  </td>
                  <td className="hidden px-4 py-3 text-right tabular-nums text-zinc-500 sm:table-cell">
                    {m.procedures > 0 ? m.procedures : '—'}
                  </td>
                </tr>
              );
            })}
          </tbody>
          {/* Totals footer */}
          <tfoot>
            <tr className="border-t-2 border-zinc-200 bg-zinc-50 font-semibold text-zinc-800">
              <td className="px-4 py-3">Total</td>
              <td className="px-4 py-3 text-right tabular-nums">{usd(totalGross)}</td>
              <td className="px-4 py-3 text-right tabular-nums text-emerald-700">{usd(totalNet)}</td>
              <td className="px-4 py-3 text-right tabular-nums">
                {overallMom != null && (
                  <span className={overallMom >= 0 ? 'text-emerald-600' : 'text-rose-500'}>
                    {overallMom >= 0 ? '↑ +' : '↓ '}{Math.abs(overallMom).toFixed(1)}%
                  </span>
                )}
              </td>
              <td className="hidden px-4 py-3 text-right tabular-nums sm:table-cell">{usd(totalCard)}</td>
              <td className="hidden px-4 py-3 text-right tabular-nums sm:table-cell">{usd(totalCash)}</td>
              <td className="hidden px-4 py-3 text-right tabular-nums sm:table-cell">{avgPayrollPct}</td>
              <td className="hidden px-4 py-3 text-right tabular-nums sm:table-cell">{usd(totalExpense)}</td>
              <td className="hidden px-4 py-3 text-right tabular-nums sm:table-cell">{totalSvc || '—'}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}
