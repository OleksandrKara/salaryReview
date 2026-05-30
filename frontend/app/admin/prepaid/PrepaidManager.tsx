'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../../lib/api';
import type { PrepaidCandidate, PrepaidPackage, Provider } from '../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

export default function PrepaidManager({
  initialPackages,
  providers,
}: {
  initialPackages: PrepaidPackage[];
  providers: Provider[];
}) {
  const router = useRouter();
  const [packages, setPackages] = useState(initialPackages);
  const [error, setError] = useState('');

  // create form
  const [customerName, setCustomerName] = useState('');
  const [customerId, setCustomerId] = useState('');
  const [providerId, setProviderId] = useState<number | ''>('');
  const [paidDate, setPaidDate] = useState('');
  const [amount, setAmount] = useState('');
  const [totalServices, setTotalServices] = useState('');
  const [invoiceRef, setInvoiceRef] = useState('');
  const [busy, setBusy] = useState(false);

  async function refresh() {
    setPackages(await fetch('/api/prepaid', { cache: 'no-store' }).then((r) => r.json()));
    router.refresh();
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await api.createPackage({
        customerName,
        customerId: customerId || null,
        providerId: Number(providerId),
        paidDate,
        amount: Number(amount),
        totalServices: Number(totalServices),
        invoiceRef: invoiceRef || null,
      });
      setCustomerName(''); setCustomerId(''); setProviderId(''); setPaidDate('');
      setAmount(''); setTotalServices(''); setInvoiceRef('');
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create package');
    } finally {
      setBusy(false);
    }
  }

  async function remove(p: PrepaidPackage) {
    if (!confirm(`Delete the prepaid package for ${p.customerName}? Its draw-downs are removed too.`)) return;
    try {
      await api.deletePackage(p.id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete');
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <form onSubmit={create} className="flex flex-wrap items-end gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
        <Field label="Customer name"><input value={customerName} onChange={(e) => setCustomerName(e.target.value)} required className="w-44 rounded border border-zinc-300 px-2 py-1.5" /></Field>
        <Field label="Square customer ID" hint="optional, enables candidate lookup"><input value={customerId} onChange={(e) => setCustomerId(e.target.value)} className="w-48 rounded border border-zinc-300 px-2 py-1.5 font-mono text-xs" /></Field>
        <Field label="Provider">
          <select value={providerId} onChange={(e) => setProviderId(Number(e.target.value) || '')} required className="rounded border border-zinc-300 px-2 py-1.5">
            <option value="">Select…</option>
            {providers.map((p) => <option key={p.id} value={p.id}>{p.displayName}</option>)}
          </select>
        </Field>
        <Field label="Paid date"><input type="date" value={paidDate} onChange={(e) => setPaidDate(e.target.value)} required className="rounded border border-zinc-300 px-2 py-1.5" /></Field>
        <Field label="Amount"><input type="number" step="0.01" min="0" value={amount} onChange={(e) => setAmount(e.target.value)} required className="w-28 rounded border border-zinc-300 px-2 py-1.5" /></Field>
        <Field label="# services"><input type="number" min="1" value={totalServices} onChange={(e) => setTotalServices(e.target.value)} required className="w-24 rounded border border-zinc-300 px-2 py-1.5" /></Field>
        <Field label="Invoice #" hint="optional"><input value={invoiceRef} onChange={(e) => setInvoiceRef(e.target.value)} className="w-28 rounded border border-zinc-300 px-2 py-1.5" /></Field>
        <button type="submit" disabled={busy} className="rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">
          {busy ? 'Adding…' : 'Add package'}
        </button>
      </form>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex flex-col gap-3">
        {packages.map((p) => <PackageCard key={p.id} pkg={p} onChanged={refresh} onDelete={() => remove(p)} />)}
        {packages.length === 0 && <p className="text-sm text-zinc-400">No prepaid packages yet.</p>}
      </div>
    </div>
  );
}

function PackageCard({ pkg, onChanged, onDelete }: { pkg: PrepaidPackage; onChanged: () => Promise<void>; onDelete: () => void }) {
  const [open, setOpen] = useState(false);
  const [candidates, setCandidates] = useState<PrepaidCandidate[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function loadCandidates() {
    setError('');
    setLoading(true);
    try {
      setCandidates(await api.getCandidates(pkg.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load candidates');
    } finally {
      setLoading(false);
    }
  }

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && candidates === null) await loadCandidates();
  }

  async function confirm(c: PrepaidCandidate) {
    setError('');
    try {
      await api.redeem(pkg.id, c);
      await onChanged();
      await loadCandidates();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to confirm');
    }
  }

  async function undo(redemptionId: number) {
    setError('');
    try {
      await api.undoRedemption(redemptionId);
      await onChanged();
      await loadCandidates();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to undo');
    }
  }

  const exhausted = pkg.balance <= 0;

  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
        <div>
          <span className="font-medium">{pkg.customerName}</span>
          <span className="ml-2 text-sm text-zinc-500">{pkg.providerName} · paid {pkg.paidDate} · {usd(pkg.amount)}</span>
          {pkg.invoiceRef && <span className="ml-2 text-xs text-zinc-400">inv {pkg.invoiceRef}</span>}
        </div>
        <div className="flex items-center gap-3 text-sm">
          <span className={`rounded px-2 py-0.5 text-xs font-medium ring-1 ${exhausted ? 'bg-zinc-100 text-zinc-500 ring-zinc-300' : 'bg-green-50 text-green-700 ring-green-300'}`}>
            {pkg.balance} of {pkg.totalServices} left
          </span>
          <button onClick={toggle} className="text-xs text-blue-600 hover:underline">{open ? 'Hide' : 'Review draw-downs'}</button>
          <button onClick={onDelete} className="text-xs text-red-500 hover:text-red-700">Delete</button>
        </div>
      </div>

      {open && (
        <div className="border-t border-zinc-200 p-4">
          {error && <p className="mb-2 text-sm text-red-600">{error}</p>}

          {pkg.redemptions.length > 0 && (
            <div className="mb-4">
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Confirmed</h3>
              <ul className="flex flex-col gap-1 text-sm">
                {pkg.redemptions.map((r) => (
                  <li key={r.id} className="flex items-center justify-between gap-3">
                    <span>{r.serviceDate} · {r.serviceName ?? r.serviceVariationId} · {usd(r.menuPrice)}{r.counts ? '' : ' (add-on)'}</span>
                    <button onClick={() => undo(r.id)} className="text-xs text-red-500 hover:text-red-700">Undo</button>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Candidate visits</h3>
          {!pkg.customerId && <p className="text-sm text-amber-600">Add the Square customer ID to this package to look up their bookings.</p>}
          {pkg.customerId && loading && <p className="text-sm text-zinc-400">Loading bookings…</p>}
          {pkg.customerId && !loading && candidates && candidates.length === 0 && (
            <p className="text-sm text-zinc-400">No un-redeemed bookings for this customer since the paid date.</p>
          )}
          {pkg.customerId && !loading && candidates && candidates.length > 0 && (
            exhausted ? (
              <p className="text-sm text-zinc-500">No credit left — further visits need payment.</p>
            ) : (
              <ul className="flex flex-col gap-1 text-sm">
                {candidates.map((c) => (
                  <li key={`${c.bookingId}-${c.serviceVariationId}`} className="flex items-center justify-between gap-3">
                    <span>{c.date}{c.time ? ` · ${c.time}` : ''} · {c.serviceName} · {usd(c.menuPrice)}{c.counts ? '' : ' (add-on)'}</span>
                    <button onClick={() => confirm(c)} className="rounded bg-green-600 px-2 py-0.5 text-xs font-medium text-white hover:bg-green-700">Confirm draw-down</button>
                  </li>
                ))}
              </ul>
            )
          )}
        </div>
      )}
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="text-sm">
      <span className="mb-1 block text-zinc-600">{label}{hint && <span className="ml-1 text-xs text-zinc-400">({hint})</span>}</span>
      {children}
    </label>
  );
}
