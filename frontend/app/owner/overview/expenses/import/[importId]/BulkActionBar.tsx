'use client';

import { useState } from 'react';
import type { ExpenseCategoryDefinition } from '../../../../../lib/types';
import CategorySelect, { type CategorySelection } from './CategorySelect';
import type { RememberDecision } from './TransactionRow';

/** Bulk category/exclude assignment for the current selection — reuses `PrepaidManager`'s
 * Set-based-selection-with-live-count pattern. "Remember" always creates a keyword rule (one or
 * more required substrings, all of which must appear in the description) — merchant-agnostic, so
 * it works regardless of whether the selection shares a normalized merchant, unlike the old
 * plain-merchant option this replaces. */
export default function BulkActionBar({
  count, onApply, onClear, busy, categories,
}: {
  count: number;
  onApply: (selection: CategorySelection, remember: RememberDecision) => Promise<void> | void;
  onClear: () => void;
  busy: boolean;
  categories: ExpenseCategoryDefinition[];
}) {
  const [selection, setSelection] = useState<CategorySelection>({});
  const [remember, setRemember] = useState(false);
  const [keywordInput, setKeywordInput] = useState('');
  const [keywords, setKeywords] = useState<string[]>([]);

  const canApply = selection.category !== undefined || selection.excludeReason !== undefined;

  function addKeyword() {
    const trimmed = keywordInput.trim();
    if (!trimmed || keywords.includes(trimmed)) return;
    setKeywords([...keywords, trimmed]);
    setKeywordInput('');
  }

  function removeKeyword(k: string) {
    setKeywords(keywords.filter((x) => x !== k));
  }

  return (
    <div className="sticky bottom-0 z-10 mt-2 flex flex-wrap items-center gap-2 rounded-lg bg-zinc-800 px-3 py-2 text-white shadow-lg">
      <span className="text-xs font-medium">{count} selected</span>
      <CategorySelect value={selection} onChange={setSelection} disabled={busy} categories={categories} />
      <label className="flex items-center gap-1.5 text-[11px] text-zinc-300">
        <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-3 w-3" />
        Remember when description contains…
      </label>
      {remember && (
        <div className="flex flex-wrap items-center gap-1">
          {keywords.map((k) => (
            <span key={k} className="flex items-center gap-1 rounded-full bg-zinc-700 px-2 py-0.5 text-[10px] text-zinc-200 ring-1 ring-inset ring-zinc-600">
              {k}
              <button type="button" onClick={() => removeKeyword(k)} className="text-zinc-400 hover:text-white" aria-label={`Remove ${k}`}>
                ×
              </button>
            </span>
          ))}
          <input
            type="text"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addKeyword(); } }}
            placeholder="e.g. PAYSEND"
            className="w-32 rounded border border-zinc-600 bg-zinc-800 px-1.5 py-1 text-[11px] text-white placeholder:text-zinc-500"
          />
          <button type="button" onClick={addKeyword} className="rounded border border-zinc-600 px-1.5 py-1 text-[11px] text-zinc-300 hover:bg-zinc-700">
            Add
          </button>
        </div>
      )}
      <button
        type="button"
        disabled={busy || !canApply}
        onClick={() => onApply(selection, { rememberKeywords: remember ? keywords : [] })}
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
