'use client';

import { useState } from 'react';
import { InfoTip } from '../../../../../components/InfoTip';
import type { BankTransaction } from '../../../../../lib/types';
import ConfidenceBadge from './ConfidenceBadge';
import CategorySelect, { CATEGORY_OPTIONS, type CategorySelection } from './CategorySelect';

const usdExact = (n: number) => Math.abs(n).toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function categoryLabel(c: string): string {
  return CATEGORY_OPTIONS.find((x) => x.value === c)?.label ?? c;
}

/** One transaction, dual-rendered: a stacked card below `sm`, a table row at `sm` and up — matching
 * `ContactsTable`'s existing `sm:hidden` / `hidden sm:block` responsive convention. Editable rows
 * (everything except duplicates) get a category/exclude picker plus a "remember for this merchant"
 * checkbox, checked by default (openspec design.md D6). */
export default function TransactionRow({
  txn, selectable, selected, onToggleSelect, onReview, busy,
}: {
  txn: BankTransaction;
  selectable: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  onReview: (selection: CategorySelection, remember: boolean) => Promise<void> | void;
  busy: boolean;
}) {
  const initialSelection: CategorySelection = txn.excludedReason
    ? { excludeReason: txn.excludedReason }
    : { category: txn.category ?? undefined };
  const [selection, setSelection] = useState<CategorySelection>(initialSelection);
  const [remember, setRemember] = useState(true);

  const editable = txn.status !== 'DUPLICATE';
  const canApply = editable && (selection.category !== undefined || selection.excludeReason !== undefined);
  const changed = selection.category !== initialSelection.category || selection.excludeReason !== initialSelection.excludeReason;

  const infoTip = txn.matchReason ? (
    <InfoTip label="Why this match" text={txn.matchReason} />
  ) : null;

  const editor = editable ? (
    <div className="flex flex-col gap-1.5">
      <CategorySelect value={selection} onChange={setSelection} disabled={busy} />
      <label className="flex items-center gap-1.5 text-[11px] text-zinc-500">
        <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-3 w-3" />
        Remember this for {txn.normalizedMerchant}
      </label>
      {changed && canApply && (
        <button
          type="button"
          disabled={busy}
          onClick={() => onReview(selection, remember)}
          className="self-start rounded bg-zinc-800 px-2 py-1 text-[11px] font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          {busy ? 'Saving…' : 'Apply'}
        </button>
      )}
    </div>
  ) : (
    <span className="text-xs text-zinc-400">
      Duplicate of transaction #{txn.duplicateOfTransactionId}
    </span>
  );

  return (
    <>
      {/* Mobile card */}
      <div className="rounded-lg p-3 ring-1 ring-zinc-200 sm:hidden">
        <div className="flex items-start justify-between gap-2">
          <label className="flex items-start gap-2">
            {selectable && (
              <input type="checkbox" checked={selected} onChange={onToggleSelect} className="mt-0.5 h-4 w-4" />
            )}
            <span>
              <div className="text-sm font-medium text-zinc-800">{txn.transactionDate}</div>
              <div className="text-xs text-zinc-500">{txn.rawDescription}</div>
            </span>
          </label>
          <span className="whitespace-nowrap text-sm font-medium tabular-nums text-zinc-800">{usdExact(txn.amount)}</span>
        </div>
        <div className="mt-2 flex items-center gap-1.5">
          <ConfidenceBadge txn={txn} />
          {txn.category && <span className="text-xs text-zinc-500">{categoryLabel(txn.category)}</span>}
          {infoTip}
        </div>
        <div className="mt-2">{editor}</div>
      </div>

      {/* Desktop row */}
      <div className="hidden items-start gap-3 rounded-lg px-3 py-2 ring-1 ring-zinc-100 sm:flex">
        {selectable && (
          <input type="checkbox" checked={selected} onChange={onToggleSelect} className="mt-1 h-4 w-4 shrink-0" />
        )}
        <div className="w-24 shrink-0 text-xs text-zinc-500">{txn.transactionDate}</div>
        <div className="w-56 shrink-0">
          <div className="text-sm text-zinc-800">{txn.normalizedMerchant}</div>
          <div className="text-xs text-zinc-400">{txn.rawDescription}</div>
        </div>
        <div className="w-24 shrink-0 text-right text-sm font-medium tabular-nums text-zinc-800">{usdExact(txn.amount)}</div>
        <div className="w-32 shrink-0">
          <div className="flex items-center gap-1">
            <ConfidenceBadge txn={txn} />
            {infoTip}
          </div>
        </div>
        <div className="flex-1">{editor}</div>
      </div>
    </>
  );
}
