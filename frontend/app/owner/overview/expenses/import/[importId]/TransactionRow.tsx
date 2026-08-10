'use client';

import { useState } from 'react';
import { InfoTip } from '../../../../../components/InfoTip';
import type { BankTransaction, ExpenseCategoryDefinition } from '../../../../../lib/types';
import ConfidenceBadge from './ConfidenceBadge';
import CategorySelect, { type CategorySelection } from './CategorySelect';

const usdExact = (n: number) => Math.abs(n).toLocaleString('en-US', { style: 'currency', currency: 'USD' });

/** What a "remember this" decision resolves to: a keyword rule (one or more required substrings,
 * all of which must be present in the description). Empty/unchecked means "don't remember
 * anything". Merchant-exact-match rules aren't offered here anymore — the normalized merchant is
 * often a unique, unreadable per-transaction blob (e.g. ad-network descriptors) that would only
 * ever match that one transaction again, so "contains" keywords are the only reliable option. */
export interface RememberDecision {
  rememberKeywords: string[];
}

const NO_REMEMBER: RememberDecision = { rememberKeywords: [] };

function RememberControl({ onChange }: { onChange: (decision: RememberDecision) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [keywordInput, setKeywordInput] = useState('');
  const [keywords, setKeywords] = useState<string[]>([]);

  function emit(nextEnabled: boolean, nextKeywords: string[]) {
    onChange(nextEnabled ? { rememberKeywords: nextKeywords } : NO_REMEMBER);
  }

  function addKeyword() {
    const trimmed = keywordInput.trim();
    if (!trimmed || keywords.includes(trimmed)) return;
    const next = [...keywords, trimmed];
    setKeywords(next);
    setKeywordInput('');
    emit(enabled, next);
  }

  function removeKeyword(k: string) {
    const next = keywords.filter((x) => x !== k);
    setKeywords(next);
    emit(enabled, next);
  }

  return (
    <div className="flex flex-col gap-1">
      <label className="flex items-center gap-1.5 text-[11px] text-zinc-500">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => { setEnabled(e.target.checked); emit(e.target.checked, keywords); }}
          className="h-3 w-3"
        />
        Remember when description contains…
      </label>
      {enabled && (
        <div className="ml-4 flex flex-col gap-1">
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
          <div className="flex min-w-0 items-center gap-1">
            <input
              type="text"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addKeyword(); } }}
              placeholder="e.g. PAYSEND"
              className="min-w-0 flex-1 rounded border border-zinc-300 px-1.5 py-1 text-[11px] sm:w-40 sm:flex-none"
            />
            <button type="button" onClick={addKeyword} className="shrink-0 rounded border border-zinc-300 px-1.5 py-1 text-[11px] text-zinc-600 hover:bg-zinc-50">
              Add
            </button>
          </div>
          <span className="text-[10px] text-zinc-400">All of the above must appear in the description.</span>
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
 * checked, it creates a keyword rule (one or more required substrings, all of which must appear
 * in the description) — useful for bank descriptors that embed a per-transaction reference
 * number, so the normalized merchant is never stable enough to match on its own. */
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
    <div className="flex min-w-0 flex-col gap-1.5">
      <CategorySelect value={selection} onChange={setSelection} disabled={busy} categories={categories} />
      <RememberControl onChange={setRemember} />
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
      <div className="min-w-0 rounded-lg p-3 ring-1 ring-zinc-200 sm:hidden">
        <div className="flex items-start justify-between gap-2">
          <label className="flex min-w-0 items-start gap-2">
            {selectable && (
              <input type="checkbox" checked={selected} onChange={onToggleSelect} className="mt-0.5 h-4 w-4 shrink-0" />
            )}
            <span className="min-w-0">
              <div className="text-sm font-medium text-zinc-800">{txn.transactionDate}</div>
              <div className="truncate text-xs text-zinc-500" title={txn.rawDescription}>{txn.rawDescription}</div>
            </span>
          </label>
          <span className="shrink-0 whitespace-nowrap text-sm font-medium tabular-nums text-zinc-800">{usdExact(txn.amount)}</span>
        </div>
        {/* flex-wrap + truncate — a long category label (e.g. "Software Subscriptions") next to the
            confidence badge and info tip has no room to sit on one line on a narrow phone; without
            wrap this row silently pushed the whole card wider than the viewport, which is what
            forces the browser to zoom the entire page out (tiny text, "wide" blocks). */}
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <ConfidenceBadge txn={txn} />
          {txn.category && (
            <span className="max-w-[12rem] truncate text-xs text-zinc-500" title={categoryLabel(txn.category, categories)}>
              {categoryLabel(txn.category, categories)}
            </span>
          )}
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
          <div className="truncate text-sm text-zinc-800" title={txn.normalizedMerchant}>{txn.normalizedMerchant}</div>
          <div className="truncate text-xs text-zinc-400" title={txn.rawDescription}>{txn.rawDescription}</div>
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
