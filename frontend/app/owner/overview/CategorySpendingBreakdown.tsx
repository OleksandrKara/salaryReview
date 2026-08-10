import type { ExpenseCategoryDefinition } from '../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: n < 1000 ? 2 : 0 });

function categoryLabel(code: string, categories: ExpenseCategoryDefinition[]): string {
  return categories.find((c) => c.code === code)?.label ?? code;
}

/** "Spending by category" for the Net tab — same amount-descending bar-list treatment as the
 * reconciliation page's CategoryBreakdown widget, but fed a pre-merged {@code Record<string,
 * number>} summed across every month in the selected range (see NetSummary) rather than a single
 * import's raw transaction list. Explains exactly where "Bank Business Expenses" + "Other Cash
 * Business Expenses" came from without an owner having to open the Expenses tab separately.
 * Provider compensation and manager time are deliberately never categories here — they already
 * have their own dedicated P&L lines. */
export default function CategorySpendingBreakdown({
  breakdown,
  categories,
}: {
  breakdown: Record<string, number>;
  categories: ExpenseCategoryDefinition[];
}) {
  const rows = Object.entries(breakdown)
    .filter(([, amount]) => amount !== 0)
    .sort(([, a], [, b]) => b - a);
  if (rows.length === 0) return null;

  const total = rows.reduce((sum, [, amount]) => sum + amount, 0);

  return (
    <div className="mt-3 rounded-lg bg-zinc-50/60 p-3 ring-1 ring-zinc-200" data-testid="net-category-breakdown">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Spending by category</h3>
        <span className="text-sm font-semibold tabular-nums text-zinc-800">{usd(total)}</span>
      </div>
      <div className="mt-2.5 flex flex-col gap-2">
        {rows.map(([category, amount]) => {
          const pct = total > 0 ? (amount / total) * 100 : 0;
          const label = categoryLabel(category, categories);
          return (
            <div key={category} className="flex items-center gap-2">
              <span className="w-24 shrink-0 truncate text-xs text-zinc-600 sm:w-36" title={label}>
                {label}
              </span>
              <div className="h-2 min-w-0 flex-1 overflow-hidden rounded-full bg-zinc-200/70">
                <div className="h-full rounded-full bg-zinc-500" style={{ width: `${Math.max(pct, 2)}%` }} />
              </div>
              <span className="w-16 shrink-0 text-right text-xs tabular-nums text-zinc-800 sm:w-20">
                {usd(amount)}
              </span>
              <span className="hidden w-9 shrink-0 text-right text-[10px] tabular-nums text-zinc-400 sm:block">
                {pct.toFixed(0)}%
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
