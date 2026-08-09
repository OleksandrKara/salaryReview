'use client';

import { useMemo } from 'react';
import type { BankTransaction, ExpenseCategoryDefinition } from '../../../../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: n < 1000 ? 2 : 0 });

function categoryLabel(code: string, categories: ExpenseCategoryDefinition[]): string {
  return categories.find((c) => c.code === code)?.label ?? code;
}

/** Live spending-by-category breakdown for this import — sums every transaction that's actually
 * been categorized (AUTO_MATCHED/REVIEWED with a category set, i.e. exactly what completing this
 * import would turn into real expense entries), grouped and sorted by amount descending. Purely
 * derived from the already-loaded transaction list, so it updates in real time as the owner
 * categorizes and stays accurate after the import is completed too — one view serves both "how am
 * I doing so far" during review and "what did this statement actually cost, by category" once
 * done. Excluded/duplicate rows are deliberately left out — they aren't real spend. */
export default function CategoryBreakdown({
  transactions, categories,
}: {
  transactions: BankTransaction[];
  categories: ExpenseCategoryDefinition[];
}) {
  const { rows, total, pendingCount } = useMemo(() => {
    const sums = new Map<string, { amount: number; count: number }>();
    let total = 0;
    let pendingCount = 0;
    for (const t of transactions) {
      if ((t.status === 'AUTO_MATCHED' || t.status === 'REVIEWED') && t.category) {
        const entry = sums.get(t.category) ?? { amount: 0, count: 0 };
        entry.amount += Math.abs(t.amount);
        entry.count += 1;
        sums.set(t.category, entry);
        total += Math.abs(t.amount);
      } else if (t.status === 'NEEDS_REVIEW') {
        pendingCount += 1;
      }
    }
    const rows = Array.from(sums.entries())
      .map(([category, { amount, count }]) => ({ category, amount, count }))
      .sort((a, b) => b.amount - a.amount);
    return { rows, total, pendingCount };
  }, [transactions]);

  if (rows.length === 0) return null;

  return (
    <div className="mt-3 rounded-lg p-3 ring-1 ring-zinc-200" data-testid="category-breakdown">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Spending by category</h2>
        <span className="text-sm font-semibold tabular-nums text-zinc-800">{usd(total)}</span>
      </div>
      <div className="mt-2.5 flex flex-col gap-2">
        {rows.map((r) => {
          const pct = total > 0 ? (r.amount / total) * 100 : 0;
          return (
            <div key={r.category} className="flex items-center gap-2">
              <span
                className="w-24 shrink-0 truncate text-xs text-zinc-600 sm:w-36"
                title={categoryLabel(r.category, categories)}
              >
                {categoryLabel(r.category, categories)}
              </span>
              <div className="h-2 min-w-0 flex-1 overflow-hidden rounded-full bg-zinc-100">
                <div className="h-full rounded-full bg-zinc-500" style={{ width: `${Math.max(pct, 2)}%` }} />
              </div>
              <span className="w-16 shrink-0 text-right text-xs tabular-nums text-zinc-800 sm:w-20">
                {usd(r.amount)}
              </span>
              <span className="hidden w-9 shrink-0 text-right text-[10px] tabular-nums text-zinc-400 sm:block">
                {pct.toFixed(0)}%
              </span>
            </div>
          );
        })}
      </div>
      {pendingCount > 0 && (
        <p className="mt-2.5 text-[11px] text-zinc-400">
          {pendingCount} transaction{pendingCount === 1 ? '' : 's'} still need review — not included above.
        </p>
      )}
    </div>
  );
}
