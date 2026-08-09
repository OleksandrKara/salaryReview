'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api } from '../../../../../lib/api';
import type { BankStatementImportDetail, BankTransaction, BankTransactionStatus, ExpenseCategoryDefinition } from '../../../../../lib/types';
import TransactionRow, { type RememberDecision } from './TransactionRow';
import BulkActionBar from './BulkActionBar';
import type { CategorySelection } from './CategorySelect';

type GroupKey = 'NEEDS_REVIEW' | 'CATEGORIZED' | 'EXCLUDED' | 'DUPLICATE';

const GROUPS: { key: GroupKey; label: string; statuses: BankTransactionStatus[] }[] = [
  { key: 'NEEDS_REVIEW', label: 'Needs Review', statuses: ['NEEDS_REVIEW'] },
  { key: 'CATEGORIZED', label: 'Automatically Categorized', statuses: ['AUTO_MATCHED', 'REVIEWED'] },
  { key: 'EXCLUDED', label: 'Excluded', statuses: ['EXCLUDED'] },
  { key: 'DUPLICATE', label: 'Duplicates', statuses: ['DUPLICATE'] },
];

export default function ReconciliationWorkspace({ importId }: { importId: number }) {
  const router = useRouter();
  const [detail, setDetail] = useState<BankStatementImportDetail | null>(null);
  const [categories, setCategories] = useState<ExpenseCategoryDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  // Needs Review expanded by default; everything else collapsed (openspec design.md §8/§10).
  const [collapsed, setCollapsed] = useState<Set<GroupKey>>(new Set(['CATEGORIZED', 'EXCLUDED', 'DUPLICATE']));
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [completing, setCompleting] = useState(false);

  async function load() {
    setError('');
    try {
      const result = await api.getStatementImport(importId);
      setDetail(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load this import.');
    } finally {
      setLoading(false);
    }
  }

  // Initial fetch on mount — a plain .then() chain (not the reusable `load` above, which is
  // called from event handlers after a mutation) so state updates happen as a subscription
  // callback, not synchronously inside the effect body.
  useEffect(() => {
    let cancelled = false;
    api.getStatementImport(importId)
      .then((result) => { if (!cancelled) setDetail(result); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load this import.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    api.listExpenseCategories().then((result) => { if (!cancelled) setCategories(result); }).catch(() => {});
    return () => { cancelled = true; };
  }, [importId]);

  const filtered = useMemo(() => {
    if (!detail) return [];
    const q = search.trim().toLowerCase();
    if (!q) return detail.transactions;
    return detail.transactions.filter(
      (t) => t.rawDescription.toLowerCase().includes(q) || t.normalizedMerchant.toLowerCase().includes(q),
    );
  }, [detail, search]);

  function groupOf(t: BankTransaction): GroupKey {
    return GROUPS.find((g) => g.statuses.includes(t.status))?.key ?? 'NEEDS_REVIEW';
  }

  function toggleGroup(key: GroupKey) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  function toggleSelect(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function reviewOne(txnId: number, selection: CategorySelection, remember: RememberDecision) {
    setRowBusyId(txnId);
    try {
      await api.reviewTransaction(importId, txnId, {
        category: selection.category,
        excludeReason: selection.excludeReason,
        rememberKeywords: remember.rememberKeywords,
      });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save this transaction.');
    } finally {
      setRowBusyId(null);
    }
  }

  async function applyBulk(selection: CategorySelection, remember: RememberDecision) {
    setBulkBusy(true);
    setError('');
    try {
      await api.bulkReviewTransactions(importId, {
        transactionIds: Array.from(selected),
        category: selection.category,
        excludeReason: selection.excludeReason,
        rememberKeywords: remember.rememberKeywords,
      });
      setSelected(new Set());
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to apply to the selection.');
    } finally {
      setBulkBusy(false);
    }
  }

  async function complete() {
    if (!detail) return;
    const eligible = detail.transactions.filter((t) => t.status === 'AUTO_MATCHED' || t.status === 'REVIEWED').length;
    const needsReview = detail.transactions.filter((t) => t.status === 'NEEDS_REVIEW').length;
    const skippedWarning = needsReview > 0
      ? `\n\n${needsReview} transaction${needsReview === 1 ? '' : 's'} still need review and will be permanently skipped — they won't be included now or later.`
      : '';
    if (!window.confirm(`This will create ${eligible} expense entries from this import.${skippedWarning} Continue?`)) return;
    setCompleting(true);
    setError('');
    try {
      await api.completeReconciliation(importId);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to complete this reconciliation.');
    } finally {
      setCompleting(false);
    }
  }

  async function revert() {
    if (!window.confirm('Revert this import? This deletes the expense entries it created and resets its transactions.')) return;
    setCompleting(true);
    setError('');
    try {
      await api.revertStatementImport(importId);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to revert this import.');
    } finally {
      setCompleting(false);
    }
  }

  async function remove() {
    if (!window.confirm('Permanently delete this import and its transactions? This can\'t be undone — use it to clean up a duplicate or wrong upload.')) return;
    setCompleting(true);
    setError('');
    try {
      await api.deleteStatementImport(importId);
      router.push('/owner/overview/expenses/history');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete this import.');
      setCompleting(false);
    }
  }

  if (loading) return <p className="mt-6 text-sm text-zinc-500">Loading…</p>;
  if (error && !detail) return <p className="mt-6 text-sm text-red-600">{error}</p>;
  if (!detail) return null;

  const { importSummary } = detail;
  const counts = Object.fromEntries(GROUPS.map((g) => [g.key, detail.transactions.filter((t) => groupOf(t) === g.key).length])) as Record<GroupKey, number>;

  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-200">
        <div>
          <div className="text-sm font-medium text-zinc-800">{importSummary.originalFilename}</div>
          <div className="text-xs text-zinc-500">
            {detail.transactions.length} transactions · {counts.CATEGORIZED} categorized · {counts.NEEDS_REVIEW} need review
            · {counts.EXCLUDED} excluded · {counts.DUPLICATE} duplicates skipped
          </div>
        </div>
        <div className="flex items-center gap-2">
          <a
            href={api.statementImportFileUrl(importId)}
            className="rounded px-2 py-1 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300 hover:bg-white"
          >
            Download original
          </a>
          {importSummary.status === 'AWAITING_REVIEW' && (
            <>
              <button
                type="button"
                disabled={completing}
                onClick={complete}
                className="rounded bg-zinc-800 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
              >
                {completing ? 'Completing…' : 'Complete Reconciliation'}
              </button>
              <button
                type="button"
                disabled={completing}
                onClick={remove}
                className="rounded px-2 py-1.5 text-xs font-medium text-red-600 ring-1 ring-red-300 hover:bg-red-50 disabled:opacity-50"
              >
                {completing ? 'Deleting…' : 'Delete import'}
              </button>
            </>
          )}
          {importSummary.status === 'COMPLETED' && (
            <>
              <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200">
                Completed
              </span>
              <button
                type="button"
                disabled={completing}
                onClick={revert}
                className="rounded px-2 py-1 text-xs font-medium text-red-600 ring-1 ring-red-300 hover:bg-red-50 disabled:opacity-50"
              >
                Revert
              </button>
            </>
          )}
          {importSummary.status === 'REVERTED' && (
            <>
              <button
                type="button"
                disabled={completing}
                onClick={remove}
                className="rounded px-2 py-1 text-xs font-medium text-red-600 ring-1 ring-red-300 hover:bg-red-50 disabled:opacity-50"
              >
                {completing ? 'Deleting…' : 'Delete import'}
              </button>
              <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-500 ring-1 ring-inset ring-zinc-200">
                Reverted
              </span>
            </>
          )}
        </div>
      </div>

      <div className="mt-3">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search merchant/description…"
          className="w-full max-w-sm rounded border border-zinc-300 px-2 py-1.5 text-sm"
        />
      </div>

      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}

      <div className="mt-3 flex flex-col gap-4">
        {GROUPS.map((g) => {
          const rows = filtered.filter((t) => groupOf(t) === g.key);
          if (rows.length === 0) return null;
          const isOpen = !collapsed.has(g.key);
          const selectable = g.key === 'NEEDS_REVIEW' || g.key === 'CATEGORIZED';
          return (
            <div key={g.key}>
              <button
                type="button"
                onClick={() => toggleGroup(g.key)}
                className="flex w-full items-center gap-1.5 text-left text-xs font-semibold uppercase tracking-wide text-zinc-500"
              >
                <span>{isOpen ? '▾' : '▸'}</span>
                {g.label} ({rows.length})
              </button>
              {isOpen && (
                <div className="mt-2 flex flex-col gap-2">
                  {rows.map((t) => (
                    <TransactionRow
                      key={t.id}
                      txn={t}
                      selectable={selectable}
                      selected={selected.has(t.id)}
                      onToggleSelect={() => toggleSelect(t.id)}
                      onReview={(selection, remember) => reviewOne(t.id, selection, remember)}
                      busy={rowBusyId === t.id}
                      categories={categories}
                    />
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {selected.size > 0 && (
        <BulkActionBar
          count={selected.size}
          onApply={applyBulk}
          onClear={() => setSelected(new Set())}
          busy={bulkBusy}
          categories={categories}
        />
      )}

      <div className="mt-6 flex gap-3 text-xs">
        <Link href="/owner/overview/expenses/history" className="text-blue-600 hover:underline">← Import history</Link>
        <Link href="/owner/overview/expenses" className="text-blue-600 hover:underline">Back to Expenses</Link>
      </div>
    </div>
  );
}
