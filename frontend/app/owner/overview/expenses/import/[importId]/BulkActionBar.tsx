'use client';

import { useState } from 'react';
import type { ExpenseCategoryDefinition } from '../../../../../lib/types';
import CategorySelect, { type CategorySelection } from './CategorySelect';
import type { RememberDecision } from './TransactionRow';

/** Bulk category/exclude assignment for the current selection — reuses `PrepaidManager`'s
 * Set-based-selection-with-live-count pattern. The "remember for this merchant" option is only
 * offered when every selected transaction shares the same merchant (openspec design.md §9): each
 * merchant's own rule needs its own decision, so a mixed-merchant batch can't safely remember one
 * — and, for the same reason, bulk-remember only ever creates a plain-merchant rule, never a
 * keyword rule (that needs per-transaction judgment, see the per-row control in TransactionRow). */
export default function BulkActionBar({
  count, sameMerchant, merchantName, onApply, onClear, busy, categories,
}: {
  count: number;
  sameMerchant: boolean;
  merchantName?: string;
  onApply: (selection: CategorySelection, remember: RememberDecision) => Promise<void> | void;
  onClear: () => void;
  busy: boolean;
  categories: ExpenseCategoryDefinition[];
}) {
  const [selection, setSelection] = useState<CategorySelection>({});
  const [remember, setRemember] = useState(false);

  const canApply = selection.category !== undefined || selection.excludeReason !== undefined;

  return (
    <div className="sticky bottom-0 z-10 mt-2 flex flex-wrap items-center gap-2 rounded-lg bg-zinc-800 px-3 py-2 text-white shadow-lg">
      <span className="text-xs font-medium">{count} selected</span>
      <CategorySelect value={selection} onChange={setSelection} disabled={busy} categories={categories} />
      {sameMerchant ? (
        <label className="flex items-center gap-1.5 text-[11px] text-zinc-300">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-3 w-3" />
          Remember for {merchantName}
        </label>
      ) : (
        <span className="text-[11px] text-zinc-400">Mixed merchants — won&apos;t be remembered as a rule</span>
      )}
      <button
        type="button"
        disabled={busy || !canApply}
        onClick={() => onApply(selection, { rememberForMerchant: sameMerchant && remember, rememberKeywords: [] })}
        className="ml-auto rounded bg-white px-3 py-1 text-xs font-medium text-zinc-800 hover:bg-zinc-100 disabled:opacity-50"
      >
        {busy ? 'Applying…' : 'Apply to selection'}
      </button>
      <button type="button" onClick={onClear} className="text-xs text-zinc-300 hover:text-white">
        Clear
      </button>
    </div>
  );
}
