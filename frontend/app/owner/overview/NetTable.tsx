'use client';

import { Fragment, useState } from 'react';
import type { ExpenseCategoryDefinition, MonthSummary } from '../../lib/types';
import CategorySpendingBreakdown from './CategorySpendingBreakdown';

const usd = (n: number | null | undefined) =>
  n == null
    ? '—'
    : n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

// Below this, the prior month's dollar figure is too close to zero for a percentage to mean
// anything — e.g. $1,300 vs a $10 prior month is a real "+13,000%" arithmetically, but useless as
// a signal. Show the dollar swing instead so the badge stays honest without a nonsensical number.
const MIN_BASE_USD = 100;

type Mom = { kind: 'pct'; value: number } | { kind: 'delta'; value: number };

function momPct(months: MonthSummary[], i: number, key: 'netRevenue' | 'grossRevenue'): Mom | null {
  if (i === 0) return null;
  const cur  = months[i][key];
  const prev = months[i - 1][key];
  if (cur == null || prev == null) return null;
  if (prev === 0) return cur === 0 ? null : { kind: 'delta', value: cur };
  if (Math.abs(prev) < MIN_BASE_USD) return { kind: 'delta', value: cur - prev };
  return { kind: 'pct', value: ((cur - prev) / prev) * 100 };
}

function MomBadge({ mom }: { mom: Mom | null }) {
  if (mom == null) return <span className="text-zinc-300">—</span>;
  const pos = mom.value >= 0;
  return (
    <span
      className={`font-semibold tabular-nums ${pos ? 'text-emerald-600' : 'text-rose-500'}`}
      title={mom.kind === 'delta' ? "Prior month's figure was too small for a percentage to be meaningful — showing the dollar change instead." : undefined}
    >
      {pos ? '↑ +' : '↓ '}{mom.kind === 'pct' ? `${Math.abs(mom.value).toFixed(1)}%` : usd(Math.abs(mom.value))}
    </span>
  );
}

/** Whether this month's Bank Business Expenses/Manager time are real bank-statement figures or
 * formula/clocked-time estimates — the exact question the owner asked about after the numbers
 * changed source without any visible explanation. Provider compensation (card + cash) always
 * comes from the Salary/Commission Report regardless of this flag (see
 * OwnerOverviewService.providerCompensationForMonth). Same color convention as `StatusBadge` in
 * `ImportHistoryList.tsx` (emerald = completed/real, amber = still pending/estimated). */
function SourceBadge({ statementCovered }: { statementCovered: boolean }) {
  return statementCovered ? (
    <span
      className="rounded-md bg-emerald-50 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200"
      title="Bank business expenses and manager time are real numbers from a completed bank-statement reconciliation for this month."
    >
      Statement
    </span>
  ) : (
    <span
      className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200"
      title="Bank business expenses and manager time are estimates (manual entries / clocked time) — complete this month's bank-statement reconciliation for real numbers."
    >
      Estimate
    </span>
  );
}

function hasCategoryBreakdown(m: MonthSummary): boolean {
  return m.categoryBreakdown != null && Object.keys(m.categoryBreakdown).length > 0;
}

function hasPersonalBreakdown(m: MonthSummary): boolean {
  return m.personalBreakdown != null && Object.keys(m.personalBreakdown).length > 0;
}

/** Cash revenue minus the provider's cash-comp share minus cash-paid business expenses — what's
 * left in the salon's own pocket from this month's cash, the same formula NetSummary's aggregate
 * "Cash Position" section already uses, just run for a single month instead of summed over the
 * whole range. Null if any input is unknown. */
function cashRemaining(m: MonthSummary): number | null {
  if (m.cashRevenue == null || m.cashProviderCompensation == null || m.cashBusinessExpenseTotal == null) return null;
  return m.cashRevenue - m.cashProviderCompensation - m.cashBusinessExpenseTotal;
}

function hasDetails(m: MonthSummary): boolean {
  return hasCategoryBreakdown(m) || hasPersonalBreakdown(m) || cashRemaining(m) != null;
}

/** The expandable "Details" panel for one month — category-of-expense breakdown, what "Personal"
 * consisted of, and how much of this month's cash revenue is left after the provider's cash share
 * and cash-paid expenses. Shared between the mobile card and desktop detail row so the two never
 * drift apart. Each section only renders when it actually has something to show. */
function MonthDetails({ m, categories }: { m: MonthSummary; categories: ExpenseCategoryDefinition[] }) {
  const cash = cashRemaining(m);
  return (
    <div className="flex flex-col gap-3">
      {hasCategoryBreakdown(m) && (
        <div>
          <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-400">Category breakdown</h4>
          <CategorySpendingBreakdown breakdown={m.categoryBreakdown!} categories={categories} />
        </div>
      )}
      {hasPersonalBreakdown(m) && (
        <div>
          <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-400">Personal spending</h4>
          <p className="mt-0.5 text-[11px] text-zinc-400">Never subtracted from Net — shown here so it&apos;s clear what it&apos;s made of.</p>
          <CategorySpendingBreakdown breakdown={m.personalBreakdown!} categories={categories} />
        </div>
      )}
      {cash != null && (
        <div className="rounded-lg bg-zinc-50/60 p-3 ring-1 ring-zinc-200">
          <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-400">Cash position</h4>
          <div className="mt-2 flex flex-col gap-1 text-xs">
            <div className="flex items-center justify-between">
              <span className="text-zinc-500">Cash revenue</span>
              <span className="tabular-nums text-zinc-700">{usd(m.cashRevenue)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-500">Provider cash comp</span>
              <span className="tabular-nums text-zinc-700">− {usd(m.cashProviderCompensation)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-500">Cash business expenses</span>
              <span className="tabular-nums text-zinc-700">− {usd(m.cashBusinessExpenseTotal)}</span>
            </div>
            <div className="mt-1 flex items-center justify-between border-t border-zinc-200 pt-1">
              <span className="font-semibold text-zinc-700">Cash remaining</span>
              <span className="font-semibold tabular-nums text-emerald-700">{usd(cash)}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** Monthly Gross → Provider Compensation (card + cash) → Expenses → Net breakdown — the cost side
 * split out of GrowthTable (see that component's own doc comment) into its own Revenue tab, since
 * "what's the gross number" and "what's actually left after payroll and expenses" are different
 * questions a manager asks at different times, not one row each in an increasingly wide table.
 * Dual-rendered: a stacked card per month below `sm` (these figures used to be entirely invisible
 * on mobile — hidden table columns with no fallback), a table at `sm` and up, matching this
 * codebase's established `TransactionRow`/`ContactsTable` convention. Every row/card also carries
 * a Statement/Estimate badge so it's clear at a glance whether that month's cost figures are real
 * bank-statement numbers or estimates, not just an aggregate banner elsewhere on the page.
 *
 * Each month with anything to drill into also gets a collapsed-by-default "Details" toggle
 * revealing up to three sections scoped to just that month (not the whole range's — see
 * NetSummary's aggregate versions above this table): its own category-of-expense breakdown, what
 * "Personal" spending was actually made of, and a Cash Position mini-summary (cash revenue minus
 * the provider's cash share minus cash-paid expenses = cash remaining) — so "which expense drove
 * this month's Net" and "where did the cash go" are a click away without a permanently wider
 * table or an always-visible breakdown per row. */
export default function NetTable({ months, categories }: { months: MonthSummary[]; categories: ExpenseCategoryDefinition[] }) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const toggle = (key: string) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });

  const active = months.filter((m) => m.grossRevenue != null);
  if (active.length === 0) return null;

  const totalGross   = active.reduce((s, m) => s + (m.grossRevenue ?? 0), 0);
  const totalPayrollCard = active.reduce((s, m) => s + (m.payrollCost ?? 0), 0);
  const totalPayrollCash = active.reduce((s, m) => s + (m.cashProviderCompensation ?? 0), 0);
  const totalManagerLabor = active.reduce((s, m) => s + (m.managerLaborCost ?? 0), 0);
  const totalExpense = active.reduce((s, m) => s + (m.expenseTotal ?? 0), 0);
  const totalCashExpense = active.reduce((s, m) => s + (m.cashBusinessExpenseTotal ?? 0), 0);
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
          const mom = momPct(active, i, 'netRevenue');
          const isLive = !m.finalized;
          const key = `${m.year}-${m.month}`;
          const canExpand = hasDetails(m);
          const isOpen = canExpand && expanded.has(key);
          return (
            <div
              key={key}
              data-testid={`net-row-mobile-${key}`}
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
                <MomBadge mom={mom} />
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
                  <span className="text-zinc-400">Provider Comp (card)</span>
                  <span className="tabular-nums text-zinc-600">{m.payrollCost != null ? `− ${usd(m.payrollCost)}` : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Provider Comp (cash)</span>
                  <span className="tabular-nums text-zinc-600">{m.cashProviderCompensation != null ? `− ${usd(m.cashProviderCompensation)}` : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Manager time</span>
                  <span className="tabular-nums text-zinc-600">{m.managerLaborCost != null ? `− ${usd(m.managerLaborCost)}` : '—'}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-zinc-400">Bank expenses</span>
                  <span className="tabular-nums text-zinc-600">{m.expenseTotal != null ? `− ${usd(m.expenseTotal)}` : '—'}</span>
                </div>
                <div className="col-span-2 flex items-center justify-between">
                  <span className="text-zinc-400">Cash business expenses</span>
                  <span className="tabular-nums text-zinc-600">{m.cashBusinessExpenseTotal != null ? `− ${usd(m.cashBusinessExpenseTotal)}` : '—'}</span>
                </div>
              </div>
              {canExpand && (
                <div className="mt-2 border-t border-zinc-100 pt-2">
                  <button
                    type="button"
                    onClick={() => toggle(key)}
                    className="flex items-center gap-1 text-xs font-medium text-zinc-500 hover:text-zinc-700"
                  >
                    <span>{isOpen ? '▾' : '▸'}</span> Details
                  </button>
                  {isOpen && (
                    <div className="mt-2">
                      <MonthDetails m={m} categories={categories} />
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
        <div className="rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-300">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-zinc-800">Total</span>
            <span className="text-xs"><MomBadge mom={overallMom} /></span>
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
              <span className="text-zinc-400">Provider Comp (card)</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalPayrollCard)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Provider Comp (cash)</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalPayrollCash)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Manager time</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalManagerLabor)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-zinc-400">Bank expenses</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalExpense)}</span>
            </div>
            <div className="col-span-2 flex items-center justify-between">
              <span className="text-zinc-400">Cash business expenses</span>
              <span className="tabular-nums font-medium text-zinc-700">− {usd(totalCashExpense)}</span>
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
              <th className="px-4 py-3 text-right">Comp (card)</th>
              <th className="px-4 py-3 text-right">Comp (cash)</th>
              <th className="px-4 py-3 text-right">Manager time</th>
              <th className="px-4 py-3 text-right">Bank exp.</th>
              <th className="px-4 py-3 text-right">Cash exp.</th>
              <th className="px-4 py-3 text-right">Net</th>
              <th className="px-4 py-3 text-right">Growth</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {active.map((m, i) => {
              const mom = momPct(active, i, 'netRevenue');
              const isLive = !m.finalized;
              const key = `${m.year}-${m.month}`;
              const canExpand = hasDetails(m);
              const isOpen = canExpand && expanded.has(key);
              return (
                <Fragment key={key}>
                  <tr data-testid={`net-row-${key}`} className="hover:bg-zinc-50">
                    <td className="px-4 py-3 font-medium text-zinc-800">
                      <div className="flex flex-wrap items-center gap-1.5">
                        {canExpand ? (
                          <button
                            type="button"
                            onClick={() => toggle(key)}
                            className="flex items-center gap-1 hover:text-zinc-600"
                          >
                            <span className="text-xs text-zinc-400">{isOpen ? '▾' : '▸'}</span>
                            <span>{m.label} <span className="text-zinc-400">&apos;{String(m.year).slice(2)}</span></span>
                          </button>
                        ) : (
                          <span>{m.label} <span className="text-zinc-400">&apos;{String(m.year).slice(2)}</span></span>
                        )}
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
                      {m.cashProviderCompensation != null ? `− ${usd(m.cashProviderCompensation)}` : '—'}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-zinc-500">
                      {m.managerLaborCost != null ? `− ${usd(m.managerLaborCost)}` : '—'}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-zinc-500">
                      {m.expenseTotal != null ? `− ${usd(m.expenseTotal)}` : '—'}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-zinc-500">
                      {m.cashBusinessExpenseTotal != null ? `− ${usd(m.cashBusinessExpenseTotal)}` : '—'}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums font-semibold text-emerald-700">
                      {usd(m.netRevenue)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <MomBadge mom={mom} />
                    </td>
                  </tr>
                  {isOpen && (
                    <tr className="bg-zinc-50/60">
                      <td colSpan={9} className="px-4 pb-4 pt-0">
                        <MonthDetails m={m} categories={categories} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-zinc-200 bg-zinc-50 font-semibold text-zinc-800">
              <td className="px-4 py-3">Total</td>
              <td className="px-4 py-3 text-right tabular-nums">{usd(totalGross)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalPayrollCard)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalPayrollCash)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalManagerLabor)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalExpense)}</td>
              <td className="px-4 py-3 text-right tabular-nums">− {usd(totalCashExpense)}</td>
              <td className="px-4 py-3 text-right tabular-nums text-emerald-700">{usd(totalNet)}</td>
              <td className="px-4 py-3 text-right tabular-nums">
                <MomBadge mom={overallMom} />
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </>
  );
}
