'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import type { ManualCredit, Provider } from '../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-zinc-600">
        {label}
        {hint && <span className="ml-1 text-xs text-zinc-400">({hint})</span>}
      </span>
      {children}
    </label>
  );
}

const input = 'w-full rounded border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none sm:w-auto';

export default function ManualCreditManager({
  initialCredits,
  providers,
}: {
  initialCredits: ManualCredit[];
  providers: Provider[];
}) {
  const router = useRouter();
  const [credits, setCredits] = useState(initialCredits);
  const [error, setError]     = useState('');
  const [busy, setBusy]       = useState(false);

  const [providerId,  setProviderId]  = useState<number | ''>('');
  const [serviceDate, setServiceDate] = useState('');
  const [gross,       setGross]       = useState('');
  const [discount,    setDiscount]    = useState('');
  const [tip,         setTip]         = useState('');
  const [serviceName, setServiceName] = useState('');

  async function refresh() {
    setCredits(await fetch('/api/manual-credits', { cache: 'no-store' }).then((r) => r.json()));
    router.refresh();
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = await fetch('/api/manual-credits', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          providerId: Number(providerId),
          serviceDate,
          gross:    Number(gross),
          discount: Number(discount || 0),
          tip:      Number(tip || 0),
          serviceName: serviceName || null,
        }),
      });
      if (!res.ok) throw new Error('Could not create the credit.');
      setProviderId(''); setServiceDate(''); setGross('');
      setDiscount(''); setTip(''); setServiceName('');
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create');
    } finally {
      setBusy(false);
    }
  }

  async function remove(c: ManualCredit) {
    if (!window.confirm(`Delete this manual credit for ${c.providerName}?`)) return;
    setError('');
    const res = await fetch(`/api/manual-credits/${c.id}`, { method: 'DELETE' });
    if (!res.ok && res.status !== 204) { setError('Could not delete.'); return; }
    await refresh();
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Form — stacks on mobile, wraps on desktop */}
      <form onSubmit={create} data-testid="manual-credit-form" className="rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
          <Field label="Provider">
            <select value={providerId} onChange={(e) => setProviderId(Number(e.target.value) || '')} required className={input}>
              <option value="">Select…</option>
              {providers.map((p) => <option key={p.id} value={p.id}>{p.displayName}</option>)}
            </select>
          </Field>
          <Field label="Service date">
            <input type="date" value={serviceDate} onChange={(e) => setServiceDate(e.target.value)} required className={input} />
          </Field>
          <Field label="Gross" hint="menu price">
            <input type="number" step="0.01" min="0" value={gross} onChange={(e) => setGross(e.target.value)} required className={`${input} sm:w-28`} />
          </Field>
          <Field label="Discount" hint="optional">
            <input type="number" step="0.01" min="0" value={discount} onChange={(e) => setDiscount(e.target.value)} className={`${input} sm:w-24`} />
          </Field>
          <Field label="Tip" hint="optional">
            <input type="number" step="0.01" min="0" value={tip} onChange={(e) => setTip(e.target.value)} className={`${input} sm:w-24`} />
          </Field>
          <Field label="Service" hint="optional">
            <input value={serviceName} onChange={(e) => setServiceName(e.target.value)} className={`${input} sm:w-44`} />
          </Field>
          <button
            type="submit"
            disabled={busy}
            data-testid="manual-credit-submit"
          className="rounded bg-zinc-900 px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50 sm:mb-px"
          >
            {busy ? 'Adding…' : 'Add credit'}
          </button>
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      </form>

      {/* Mobile: cards */}
      <div data-testid="manual-credit-list" className="flex flex-col gap-3 sm:hidden">
        {credits.length === 0 && (
          <p className="py-4 text-center text-sm text-zinc-400">No manual credits.</p>
        )}
        {credits.map((c) => (
          <div key={c.id} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-medium text-zinc-800">{c.providerName}</p>
                <p className="text-sm text-zinc-500">{c.serviceDate}</p>
                {c.serviceName && <p className="mt-0.5 text-sm text-zinc-400">{c.serviceName}</p>}
              </div>
              <div className="text-right">
                <p className="font-semibold tabular-nums text-zinc-800">{usd(c.gross)}</p>
                {(c.discount > 0 || c.tip > 0) && (
                  <p className="text-xs text-zinc-400">
                    {c.discount > 0 && `−${usd(c.discount)} disc`}
                    {c.discount > 0 && c.tip > 0 && ' · '}
                    {c.tip > 0 && `${usd(c.tip)} tip`}
                  </p>
                )}
              </div>
            </div>
            <div className="mt-3 border-t border-zinc-100 pt-3">
              <button data-testid={`manual-credit-delete-${c.id}`} onClick={() => remove(c)} className="text-xs text-red-500 hover:text-red-700">
                Delete
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Desktop: table */}
      <div data-testid="manual-credit-table" className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Provider</th>
              <th className="px-3 py-2">Date</th>
              <th className="px-3 py-2 text-right">Gross</th>
              <th className="px-3 py-2 text-right">Discount</th>
              <th className="px-3 py-2 text-right">Tip</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {credits.map((c) => (
              <tr key={c.id} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">{c.providerName}</td>
                <td className="px-3 py-2 tabular-nums text-zinc-600">{c.serviceDate}</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd(c.gross)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-500">
                  {c.discount > 0 ? `−${usd(c.discount)}` : '—'}
                </td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-500">
                  {c.tip > 0 ? usd(c.tip) : '—'}
                </td>
                <td className="px-3 py-2 text-zinc-500">{c.serviceName ?? '—'}</td>
                <td className="px-3 py-2 text-right">
                  <button data-testid={`manual-credit-delete-${c.id}`} onClick={() => remove(c)} className="text-xs text-red-500 hover:text-red-700">
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {credits.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-4 text-center text-zinc-400">
                  No manual credits.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
