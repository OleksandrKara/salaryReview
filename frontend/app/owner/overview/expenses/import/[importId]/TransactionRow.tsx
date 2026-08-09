'use client';

import { useState } from 'react';
import { InfoTip } from '../../../../../components/InfoTip';
import type { BankTransaction, ExpenseCategoryDefinition } from '../../../../../lib/types';
import ConfidenceBadge from './ConfidenceBadge';
import CategorySelect, { type CategorySelection } from './CategorySelect';

const usdExact = (n: number) => Math.abs(n).toLocaleString('en-US', { style: 'currency', currency: 'USD' });

/** What a "remember this" decision resolves to: either a plain-merchant rule, or a keyword rule
 * (one or more required substrings — all must be present in the description). Empty/unchecked
 * means "don't remember anything". */
export interface RememberDecision {
  rememberForMerchant: boolean;
  rememberKeywords: string[];
}

const NO_REMEMBER: RememberDecision = { rememberForMerchant: false, rememberKeywords: [] };

function RememberControl({ merchantName, onChange }: { merchantName: string; onChange: (decision: RememberDecision) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [mode, setMode] = useState<'merchant' | 'keyword'>('merchant');
  const [keywordInput, setKeywordInput] = useState('');
  const [keywords, setKeywords] = useState<string[]>([]);

  function emit(nextEnabled: boolean, nextMode: 'merchant' | 'keyword', nextKeywords: string[]) {
    if (!nextEnabled) { onChange(NO_REMEMBER); return; }
    if (nextMode === 'merchant') { onChange({ rememberForMerchant: true, rememberKeywords: [] }); return; }
    onChange({ rememberForMerchant: false, rememberKeywords: nextKeywords });
  }

  function addKeyword() {
    const trimmed = keywordInput.trim();
    if (!trimmed || keywords.includes(trimmed)) return;
    const next = [...keywords, trimmed];
    setKeywords(next);
    setKeywordInput('');
    emit(enabled, mode, next);
  }

  function removeKeyword(k: string) {
    const next = keywords.filter((x) => x !== k);
    setKeywords(next);
    emit(enabled, mode, next);
  }

  return (
    <div className="flex flex-col gap-1">
      <label className="flex items-center gap-1.5 text-[11px] text-zinc-500">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => { setEnabled(e.target.checked); emit(e.target.checked, mode, keywords); }}
          className="h-3 w-3"
        />
        Remember this
      </label>
      {enabled && (
        <div className="ml-4 flex flex-col gap-1">
          <div className="flex items-center gap-2 text-[11px] text-zinc-500">
            <label className="flex items-center gap-1">
              <input
                type="radio"
                checked={mode === 'merchant'}
                onChange={() => { setMode('merchant'); emit(enabled, 'merchant', keywords); }}
                className="h-3 w-3"
              />
              for {merchantName}
            </label>
            <label className="flex items-center gap-1">
              <input
                type="radio"
                checked={mode === 'keyword'}
                onChange={() => { setMode('keyword'); emit(enabled, 'keyword', keywords); }}
                className="h-3 w-3"
              />
              when description contains…
            </label>
          </div>
          {mode === 'keyword' && (
            <div className="flex flex-col gap-1">
              <div className="flex flex-wrap gap-1">
                {keywords.map((k) => (
                  <span key={k} className="flex items-center gap-1 rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] text-zinc-600 ring-1 ring-inset ring-zinc-200">
                    {k}
                    <button type="button" onClick={() => removeKeyword(k)} className="text-zinc-400 hover:text-zinc-700" aria-label={`Remove ${k}`}>
                      ×
                    </button>
                  </span>
                ))}
              </div>
              <div className="flex items-center gap-1">
                <input
                  type="text"
                  value={keywordInput}
                  onChange={(e) => setKeywordInput(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addKeyword(); } }}
                  placeholder="e.g. PAYSEND"
                  className="w-40 rounded border border-zinc-300 px-1.5 py-1 text-[11px]"
                />
                <button type="button" onClick={addKeyword} className="rounded border border-zinc-300 px-1.5 py-1 text-[11px] text-zinc-600 hover:bg-zinc-50">
                  Add
                </button>
              </div>
              <span className="text-[10px] text-zinc-400">All of the above must appear in the description.</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function categoryLabel(c: string, categories: ExpenseCategoryDefinition[]): string {
  return categories.find((x) => x.code === c)?.label ?? c;
}

/** One transaction, dual-rendered: a stacked card below `sm`, a table row at `sm` and up — matching
 * `ContactsTable`'s existing `sm:hidden` / `hidden sm:block` responsive convention. Editable rows
 * (everything except duplicates) get a category/exclude picker plus a "remember" checkbox,
 * unchecked by default so a rule is only created when the owner deliberately opts in. When
 * checked, it can create either a plain-merchant rule or a keyword rule (one or more required
 * substrings, all of which must appear in the description) — useful for bank descriptors that
 * embed a per-transaction reference number, so the normalized merchant is never stable enough to
 * match on its own. */
export default function TransactionRow({
  txn, selectable, selected, onToggleSelect, onReview, busy, categories,
}: {
  txn: BankTransaction;
  selectable: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  onReview: (selection: CategorySelection, remember: RememberDecision) => Promise<void> | void;
  busy: boolean;
  categories: ExpenseCategoryDefinition[];
}) {
  const initialSelection: CategorySelection = txn.excludedReason
    ? { excludeReason: txn.excludedReason }
    : { category: txn.category ?? undefined };
  const [selection, setSelection] = useState<CategorySelection>(initialSelection);
  const [remember, setRemember] = useState<RememberDecision>(NO_REMEMBER);

  const editable = txn.status !== 'DUPLICATE';
  const canApply = editable && (selection.category !== undefined || selection.excludeReason !== undefined);
  const changed = selection.category !== initialSelection.category || selection.excludeReason !== initialSelection.excludeReason;

  const infoTip = txn.matchReason ? (
    <InfoTip label="Why this match" text={txn.matchReason} />
  ) : null;

  const editor = editable ? (
    <div className="flex flex-col gap-1.5">
      <CategorySelect value={selection} onChange={setSelection} disabled={busy} categories={categories} />
      <RememberControl merchantName={txn.normalizedMerchant} onChange={setRemember} />
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
            <span className="min-w-0">
              <div className="text-sm font-medium text-zinc-800">{txn.transactionDate}</div>
              <div className="break-words text-xs text-zinc-500">{txn.rawDescription}</div>
            </span>
          </label>
          <span className="whitespace-nowrap text-sm font-medium tabular-nums text-zinc-800">{usdExact(txn.amount)}</span>
        </div>
        <div className="mt-2 flex items-center gap-1.5">
          <ConfidenceBadge txn={txn} />
          {txn.category && <span className="text-xs text-zinc-500">{categoryLabel(txn.category, categories)}</span>}
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
        <div className="w-56 shrink-0 overflow-hidden">
          <div className="break-words text-sm text-zinc-800">{txn.normalizedMerchant}</div>
          <div className="break-words text-xs text-zinc-400">{txn.rawDescription}</div>
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
