'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../../lib/api';
import type { CustomerMatch, PrepaidCandidate, PrepaidInvoice, PrepaidPackage } from '../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

export default function PrepaidManager({
  initialPackages,
}: {
  initialPackages: PrepaidPackage[];
}) {
  const router = useRouter();
  const [packages, setPackages] = useState(initialPackages);
  const [error, setError] = useState('');

  // create form — search a customer by name, then their invoice prefills the amount/date/#.
  const [customerName, setCustomerName] = useState('');
  const [customerId, setCustomerId] = useState('');
  const [paidDate, setPaidDate] = useState('');
  const [amount, setAmount] = useState('');
  const [totalServices, setTotalServices] = useState('');
  const [invoiceRef, setInvoiceRef] = useState('');
  const [busy, setBusy] = useState(false);
  // customer + invoice lookup
  const [matches, setMatches] = useState<CustomerMatch[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [invoices, setInvoices] = useState<PrepaidInvoice[] | null>(null);
  const [loadingInvoices, setLoadingInvoices] = useState(false);
  const [pickedInvoiceId, setPickedInvoiceId] = useState('');

  async function refresh() {
    setPackages(await fetch('/api/prepaid', { cache: 'no-store' }).then((r) => r.json()));
    router.refresh();
  }

  async function searchCustomers() {
    setError('');
    if (!customerName.trim()) return;
    setSearching(true);
    try {
      setMatches(await api.searchPrepaidCustomers(customerName.trim()));
    } catch {
      setError('Customer search failed.');
    } finally {
      setSearching(false);
    }
  }

  async function pickCustomer(c: CustomerMatch) {
    setCustomerName(c.name);
    setCustomerId(c.id);
    setMatches(null);
    setInvoices(null);
    setPickedInvoiceId('');
    setLoadingInvoices(true);
    try {
      setInvoices(await api.getCustomerInvoices(c.id));
    } catch {
      setError('Could not load invoices for this customer.');
    } finally {
      setLoadingInvoices(false);
    }
  }

  function pickInvoice(inv: PrepaidInvoice) {
    setPickedInvoiceId(inv.id);
    setAmount(String(inv.amount));
    if (inv.date) setPaidDate(inv.date);
    if (inv.number) setInvoiceRef(inv.number);
  }

  function clearCustomer() {
    setCustomerId('');
    setMatches(null);
    setInvoices(null);
    setPickedInvoiceId('');
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await api.createPackage({
        customerName,
        customerId: customerId || null,
        paidDate,
        amount: Number(amount),
        totalServices: Number(totalServices),
        invoiceRef: invoiceRef || null,
      });
      setCustomerName(''); setCustomerId(''); setPaidDate('');
      setAmount(''); setTotalServices(''); setInvoiceRef('');
      setMatches(null); setInvoices(null); setPickedInvoiceId('');
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
      <form onSubmit={create} className="flex flex-col gap-4 rounded-lg p-4 ring-1 ring-zinc-200">
        {/* 1 · find the customer by name */}
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Customer name" hint="search Square by name">
            <div className="flex gap-2">
              <input
                value={customerName}
                onChange={(e) => { setCustomerName(e.target.value); setCustomerId(''); }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); searchCustomers(); } }}
                required
                className="w-56 rounded border border-zinc-300 px-2 py-1.5"
              />
              <button type="button" onClick={searchCustomers} disabled={searching || !customerName.trim()}
                className="rounded bg-zinc-100 px-3 py-1.5 text-sm ring-1 ring-zinc-300 hover:bg-zinc-200 disabled:opacity-50">
                {searching ? '…' : 'Search'}
              </button>
            </div>
          </Field>
          {customerId && (
            <span className="mb-1.5 text-xs text-green-700">
              ✓ linked to Square customer
              <button type="button" onClick={clearCustomer} className="ml-1 text-zinc-400 underline hover:text-zinc-600">change</button>
            </span>
          )}
        </div>

        {matches && !customerId && (
          matches.length === 0 ? (
            <p className="text-sm text-zinc-500">No customer matches — refine the name, or just type it and fill the fields below (candidate lookup needs a matched customer).</p>
          ) : (
            <ul className="divide-y divide-zinc-100 rounded-lg text-sm ring-1 ring-zinc-200">
              {matches.map((m) => (
                <li key={m.id} className="flex items-center justify-between gap-3 px-3 py-1.5">
                  <span>{m.name || '(no name)'}<span className="ml-2 text-xs text-zinc-400">{m.id}</span></span>
                  <button type="button" onClick={() => pickCustomer(m)}
                    className="rounded bg-zinc-100 px-2.5 py-1 text-xs font-medium ring-1 ring-zinc-300 hover:bg-zinc-200">Select</button>
                </li>
              ))}
            </ul>
          )
        )}

        {/* 2 · pick the prepaid invoice for that customer */}
        {customerId && (
          <div>
            <span className="mb-1 block text-sm text-zinc-600">
              Invoice {loadingInvoices && <span className="text-xs text-zinc-400">loading…</span>}
            </span>
            {invoices && invoices.length > 0 ? (
              <ul className="divide-y divide-zinc-100 rounded-lg text-sm ring-1 ring-zinc-200">
                {invoices.map((inv) => (
                  <li key={inv.id} className={`flex items-center justify-between gap-3 px-3 py-1.5 ${pickedInvoiceId === inv.id ? 'bg-green-50' : ''}`}>
                    <span>
                      {inv.date ?? '—'} · {inv.number ? `#${inv.number}` : '(no #)'}{inv.title ? ` · ${inv.title}` : ''} · {usd(inv.amount)}
                      <span className="ml-2 text-xs text-zinc-400">{inv.status}</span>
                    </span>
                    <button type="button" onClick={() => pickInvoice(inv)}
                      className="rounded bg-zinc-100 px-2.5 py-1 text-xs font-medium ring-1 ring-zinc-300 hover:bg-zinc-200">Use</button>
                  </li>
                ))}
              </ul>
            ) : invoices && !loadingInvoices ? (
              <p className="text-sm text-zinc-500">No invoices for this customer — enter the amount manually below.</p>
            ) : null}
          </div>
        )}

        {/* 3 · details (prefilled from the invoice, editable) */}
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Paid date"><input type="date" value={paidDate} onChange={(e) => setPaidDate(e.target.value)} required className="rounded border border-zinc-300 px-2 py-1.5" /></Field>
          <Field label="Amount"><input type="number" step="0.01" min="0" value={amount} onChange={(e) => setAmount(e.target.value)} required className="w-28 rounded border border-zinc-300 px-2 py-1.5" /></Field>
          <Field label="# services"><input type="number" min="1" value={totalServices} onChange={(e) => setTotalServices(e.target.value)} required className="w-24 rounded border border-zinc-300 px-2 py-1.5" /></Field>
          <Field label="Invoice #" hint="optional"><input value={invoiceRef} onChange={(e) => setInvoiceRef(e.target.value)} className="w-28 rounded border border-zinc-300 px-2 py-1.5" /></Field>
          <button type="submit" disabled={busy} className="rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">
            {busy ? 'Adding…' : 'Add package'}
          </button>
        </div>
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
          <span className="ml-2 text-sm text-zinc-500">paid {pkg.paidDate} · {usd(pkg.amount)}</span>
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
                    <span>{r.serviceDate} · {r.serviceName ?? r.serviceVariationId} · {r.providerName} · {usd(r.menuPrice)}{r.counts ? '' : ' (add-on)'}</span>
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
                    <span>{c.date}{c.time ? ` · ${c.time}` : ''} · {c.serviceName} · {c.providerName} · {usd(c.menuPrice)}{c.counts ? '' : ' (add-on)'}</span>
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
