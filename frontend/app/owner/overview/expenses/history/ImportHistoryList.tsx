'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '../../../../lib/api';
import type { BankStatementImportSummary } from '../../../../lib/types';

const STATUS_STYLE: Record<string, string> = {
  AWAITING_REVIEW: 'bg-amber-50 text-amber-700 ring-amber-200',
  COMPLETED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  REVERTED: 'bg-zinc-100 text-zinc-500 ring-zinc-200',
};

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ring-1 ring-inset ${STATUS_STYLE[status] ?? ''}`}>
      {status.replace('_', ' ')}
    </span>
  );
}

/** Every uploaded statement, most recent first — reopen (still `AWAITING_REVIEW`), revert (if
 * `COMPLETED`), or re-download the original file at any time (openspec design.md §19). */
export default function ImportHistoryList() {
  const [imports, setImports] = useState<BankStatementImportSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);

  async function load() {
    try {
      setImports(await api.listStatementImports());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load import history.');
    } finally {
      setLoading(false);
    }
  }

  // Initial fetch on mount — a plain .then() chain, not the reusable `load` above (called from
  // event handlers after a mutation), so state updates happen as a subscription callback rather
  // than synchronously inside the effect body.
  useEffect(() => {
    let cancelled = false;
    api.listStatementImports()
      .then((result) => { if (!cancelled) setImports(result); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load import history.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  async function revert(id: number) {
    if (!window.confirm('Revert this import? This deletes the expense entries it created and resets its transactions.')) return;
    setBusyId(id);
    setError('');
    try {
      await api.revertStatementImport(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to revert this import.');
    } finally {
      setBusyId(null);
    }
  }

  if (loading) return <p className="mt-6 text-sm text-zinc-500">Loading…</p>;

  return (
    <div className="mt-4">
      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}
      {imports.length === 0 ? (
        <p className="text-sm text-zinc-500">No statements imported yet.</p>
      ) : (
        <div className="flex flex-col gap-2">
          {imports.map((imp) => (
            <div key={imp.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg p-3 ring-1 ring-zinc-200">
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-zinc-800">{imp.originalFilename}</span>
                  <StatusBadge status={imp.status} />
                </div>
                <div className="text-xs text-zinc-500">
                  {imp.rowCount} transactions
                  {imp.statementPeriodStart && imp.statementPeriodEnd
                    ? ` · ${imp.statementPeriodStart} – ${imp.statementPeriodEnd}`
                    : ''}
                  {' · uploaded by '}{imp.uploadedBy ?? 'unknown'}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <a
                  href={api.statementImportFileUrl(imp.id)}
                  className="rounded px-2 py-1 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300 hover:bg-zinc-50"
                >
                  Download
                </a>
                {imp.status !== 'REVERTED' && (
                  <Link
                    href={`/owner/overview/expenses/import/${imp.id}`}
                    className="rounded px-2 py-1 text-xs font-medium text-blue-600 ring-1 ring-blue-200 hover:bg-blue-50"
                  >
                    {imp.status === 'AWAITING_REVIEW' ? 'Reopen' : 'View'}
                  </Link>
                )}
                {imp.status === 'COMPLETED' && (
                  <button
                    type="button"
                    disabled={busyId === imp.id}
                    onClick={() => revert(imp.id)}
                    className="rounded px-2 py-1 text-xs font-medium text-red-600 ring-1 ring-red-300 hover:bg-red-50 disabled:opacity-50"
                  >
                    {busyId === imp.id ? 'Reverting…' : 'Revert'}
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
