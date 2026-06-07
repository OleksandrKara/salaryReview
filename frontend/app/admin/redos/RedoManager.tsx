'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import type { Provider, Redo } from '../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-zinc-600">{label}</span>
      {children}
    </label>
  );
}

const input = 'w-full rounded border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none sm:w-auto';

export default function RedoManager({
  initialRedos,
  providers,
}: {
  initialRedos: Redo[];
  providers: Provider[];
}) {
  const router = useRouter();
  const [redos, setRedos] = useState(initialRedos);
  const [error, setError] = useState('');
  const [busy, setBusy]   = useState(false);

  const [originalProviderId, setOriginalProviderId] = useState<number | ''>('');
  const [redoProviderId,     setRedoProviderId]     = useState<number | ''>('');
  const [originalDate,       setOriginalDate]       = useState('');
  const [redoDate,           setRedoDate]           = useState('');
  const [amount,             setAmount]             = useState('');
  const [serviceName,        setServiceName]        = useState('');

  async function refresh() {
    setRedos(await fetch('/api/redos', { cache: 'no-store' }).then((r) => r.json()));
    router.refresh();
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (originalProviderId === redoProviderId) {
      setError('The redo provider must differ from the original.');
      return;
    }
    setBusy(true);
    try {
      const res = await fetch('/api/redos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          originalProviderId: Number(originalProviderId),
          redoProviderId:     Number(redoProviderId),
          originalDate, redoDate,
          amount:      Number(amount),
          serviceName: serviceName || null,
        }),
      });
      if (!res.ok) throw new Error('Could not create the redo.');
      setOriginalProviderId(''); setRedoProviderId('');
      setOriginalDate(''); setRedoDate('');
      setAmount(''); setServiceName('');
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create');
    } finally {
      setBusy(false);
    }
  }

  async function remove(r: Redo) {
    if (!window.confirm(`Delete this redo (${r.originalProviderName} → ${r.redoProviderName})?`)) return;
    setError('');
    const res = await fetch(`/api/redos/${r.id}`, { method: 'DELETE' });
    if (!res.ok && res.status !== 204) { setError('Could not delete.'); return; }
    await refresh();
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Form — stacks on mobile, wraps on desktop */}
      <form onSubmit={create} data-testid="redo-form" className="rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
          <Field label="Original provider">
            <select
              value={originalProviderId}
              onChange={(e) => setOriginalProviderId(Number(e.target.value) || '')}
              required
              className={input}
            >
              <option value="">Select…</option>
              {providers.map((p) => <option key={p.id} value={p.id}>{p.displayName}</option>)}
            </select>
          </Field>
          <Field label="Original date">
            <input type="date" value={originalDate} onChange={(e) => setOriginalDate(e.target.value)} required className={input} />
          </Field>
          <Field label="Redo provider">
            <select
              value={redoProviderId}
              onChange={(e) => setRedoProviderId(Number(e.target.value) || '')}
              required
              className={input}
            >
              <option value="">Select…</option>
              {providers.map((p) => <option key={p.id} value={p.id}>{p.displayName}</option>)}
            </select>
          </Field>
          <Field label="Redo date">
            <input type="date" value={redoDate} onChange={(e) => setRedoDate(e.target.value)} required className={input} />
          </Field>
          <Field label="Service amount">
            <input type="number" step="0.01" min="0" value={amount} onChange={(e) => setAmount(e.target.value)} required className={`${input} sm:w-28`} />
          </Field>
          <Field label="Service (optional)">
            <input value={serviceName} onChange={(e) => setServiceName(e.target.value)} className={`${input} sm:w-40`} />
          </Field>
          <button
            type="submit"
            disabled={busy}
            data-testid="redo-submit"
          className="rounded bg-zinc-900 px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50 sm:mb-px"
          >
            {busy ? 'Adding…' : 'Add redo'}
          </button>
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      </form>

      {/* Mobile: cards */}
      <div data-testid="redo-list" className="flex flex-col gap-3 sm:hidden">
        {redos.length === 0 && (
          <p className="py-4 text-center text-sm text-zinc-400">No redos recorded.</p>
        )}
        {redos.map((r) => (
          <div key={r.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-sm text-zinc-500">From</p>
                <p className="font-medium text-zinc-800">{r.originalProviderName}</p>
                <p className="text-xs text-zinc-400">{r.originalDate}</p>
              </div>
              <div className="text-zinc-400">→</div>
              <div>
                <p className="text-sm text-zinc-500">To</p>
                <p className="font-medium text-zinc-800">{r.redoProviderName}</p>
                <p className="text-xs text-zinc-400">{r.redoDate}</p>
              </div>
              <div className="ml-auto text-right">
                <p className="font-semibold tabular-nums text-zinc-800">{usd(r.amount)}</p>
                {r.serviceName && <p className="text-xs text-zinc-400">{r.serviceName}</p>}
              </div>
            </div>
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <button data-testid={`redo-delete-${r.id}`} onClick={() => remove(r)} className="text-xs text-red-500 hover:text-red-700">
                Delete
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Desktop: table */}
      <div data-testid="redo-table" className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">From (original)</th>
              <th className="px-3 py-2">Original date</th>
              <th className="px-3 py-2">To (redo)</th>
              <th className="px-3 py-2">Redo date</th>
              <th className="px-3 py-2 text-right">Amount</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {redos.map((r) => (
              <tr key={r.id} className="hover:bg-zinc-50">
                <td className="px-3 py-2 text-zinc-600">{r.originalProviderName}</td>
                <td className="px-3 py-2 tabular-nums text-zinc-600">{r.originalDate}</td>
                <td className="px-3 py-2 font-medium">{r.redoProviderName}</td>
                <td className="px-3 py-2 tabular-nums text-zinc-600">{r.redoDate}</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd(r.amount)}</td>
                <td className="px-3 py-2 text-zinc-500">{r.serviceName ?? '—'}</td>
                <td className="px-3 py-2 text-right">
                  <button data-testid={`redo-delete-${r.id}`} onClick={() => remove(r)} className="text-xs text-red-500 hover:text-red-700">
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {redos.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-4 text-center text-zinc-400">
                  No redos recorded.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
