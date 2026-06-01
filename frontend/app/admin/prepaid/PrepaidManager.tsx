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
  const [picked, setPicked] = useState<Set<string>>(new Set());

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
    setPicked(new Set());
    setLoadingInvoices(true);
    try {
      setInvoices(await api.getCustomerInvoices(c.id));
    } catch {
      setError('Could not load invoices for this customer.');
    } finally {
      setLoadingInvoices(false);
    }
  }

  // Toggle an invoice in/out of the selection and re-derive the package fields from ALL selected:
  // amount = sum, invoice # = the numbers joined, paid date = the earliest selected.
  function toggleInvoice(inv: PrepaidInvoice) {
    const next = new Set(picked);
    if (next.has(inv.id)) next.delete(inv.id); else next.add(inv.id);
    setPicked(next);
    const sel = (invoices ?? []).filter((i) => next.has(i.id));
    setAmount(sel.length ? sel.reduce((s, i) => s + i.amount, 0).toFixed(2) : '');
    setInvoiceRef(sel.map((i) => i.number).filter(Boolean).join(', '));
    const dates = sel.map((i) => i.date).filter((d): d is string => !!d).sort();
    if (dates.length) setPaidDate(dates[0]);
  }

  function clearCustomer() {
    setCustomerId('');
    setMatches(null);
    setInvoices(null);
    setPicked(new Set());
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
      setMatches(null); setInvoices(null); setPicked(new Set());
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create package');
    } finally {
      setBusy(false);
    }
  }

  // One customer can have several prepaid packages (e.g. paid by multiple invoices, or topped up) —
  // show them aggregated as a single entry, not separate cards.
  const customerGroups = Object.values(
    packages.reduce<Record<string, PrepaidPackage[]>>((acc, p) => {
      const key = p.customerId || `name:${p.customerName}`;
      (acc[key] ||= []).push(p);
      return acc;
    }, {}),
  );

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

        {/* 2 · pick the prepaid (PAID) invoice(s) for that customer — select one or several */}
        {customerId && (
          <div>
            <span className="mb-1 block text-sm text-zinc-600">
              Paid invoices {loadingInvoices && <span className="text-xs text-zinc-400">loading…</span>}
              {picked.size > 0 && <span className="text-xs text-zinc-500"> · {picked.size} selected</span>}
            </span>
            {invoices && invoices.length > 0 ? (
              <ul className="divide-y divide-zinc-100 rounded-lg text-sm ring-1 ring-zinc-200">
                {invoices.map((inv) => (
                  <li key={inv.id} className={`px-3 py-1.5 ${picked.has(inv.id) ? 'bg-green-50' : ''}`}>
                    <label className="flex cursor-pointer items-center gap-2.5">
                      <input type="checkbox" checked={picked.has(inv.id)} onChange={() => toggleInvoice(inv)} className="h-4 w-4" />
                      <span>
                        {inv.date ?? '—'} · {inv.number ? `#${inv.number}` : '(no #)'}{inv.title ? ` · ${inv.title}` : ''} · {usd(inv.amount)}
                      </span>
                    </label>
                  </li>
                ))}
              </ul>
            ) : invoices && !loadingInvoices ? (
              <p className="text-sm text-zinc-500">No paid invoices for this customer — enter the amount manually below.</p>
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
        {customerGroups.map((pkgs) => <CustomerCard key={pkgs[0].id} packages={pkgs} onChanged={refresh} />)}
        {packages.length === 0 && <p className="text-sm text-zinc-400">No prepaid packages yet.</p>}
      </div>
    </div>
  );
}

// All of one customer's prepaid packages, shown as a single aggregated entry.
function CustomerCard({ packages, onChanged }: { packages: PrepaidPackage[]; onChanged: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [candidates, setCandidates] = useState<PrepaidCandidate[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [picked, setPicked] = useState<Set<string>>(new Set()); // selected candidate visits
  const [confirming, setConfirming] = useState(false);

  const byDate = [...packages].sort((a, b) => a.paidDate.localeCompare(b.paidDate));
  const customerName = packages[0].customerName;
  const customerId = packages.find((p) => p.customerId)?.customerId ?? null;
  const totalServices = packages.reduce((s, p) => s + p.totalServices, 0);
  const balance = packages.reduce((s, p) => s + p.balance, 0);
  const amount = packages.reduce((s, p) => s + p.amount, 0);
  const redemptions = packages.flatMap((p) => p.redemptions);
  const invoiceRefs = [...new Set(packages.map((p) => p.invoiceRef).filter(Boolean))].join(', ');
  const exhausted = balance <= 0;
  const lookupPkg = byDate.find((p) => p.customerId) ?? byDate[0]; // earliest payment → widest candidate window

  async function loadCandidates() {
    if (!customerId) return;
    setError('');
    setLoading(true);
    try {
      setCandidates(await api.getCandidates(lookupPkg.id));
      setPicked(new Set());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load candidates');
    } finally {
      setLoading(false);
    }
  }

  const candKey = (c: PrepaidCandidate) => `${c.bookingId}-${c.serviceVariationId}`;

  function togglePick(c: PrepaidCandidate) {
    const k = candKey(c);
    const next = new Set(picked);
    if (next.has(k)) next.delete(k); else next.add(k);
    setPicked(next);
  }

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && candidates === null) await loadCandidates();
  }

  // Confirm all selected visits at once. Each draws from the earliest package that still has credit
  // (tracked locally so multiple in one batch spread correctly across packages); capped by balance.
  async function confirmSelected() {
    const chosen = (candidates ?? []).filter((c) => picked.has(candKey(c)));
    if (chosen.length === 0) return;
    if (chosen.length > balance) {
      setError(`Only ${balance} credit${balance === 1 ? '' : 's'} left — deselect ${chosen.length - balance}.`);
      return;
    }
    setError('');
    setConfirming(true);
    try {
      const remaining = new Map(byDate.map((p) => [p.id, p.balance]));
      for (const c of chosen) {
        const target = byDate.find((p) => (remaining.get(p.id) ?? 0) > 0);
        if (!target) { setError('No credit left.'); break; }
        await api.redeem(target.id, c);
        remaining.set(target.id, (remaining.get(target.id) ?? 0) - 1);
      }
      await onChanged();
      await loadCandidates();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to confirm');
    } finally {
      setConfirming(false);
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

  async function removeAll() {
    if (!window.confirm(`Delete ALL prepaid for ${customerName}? Its draw-downs are removed too.`)) return;
    setError('');
    try {
      for (const p of packages) await api.deletePackage(p.id);
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete');
    }
  }

  const paidLabel = byDate.length === 1
    ? `paid ${byDate[0].paidDate}`
    : `${byDate.length} payments · ${byDate[0].paidDate}–${byDate[byDate.length - 1].paidDate}`;

  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
        <div>
          <span className="font-medium">{customerName}</span>
          <span className="ml-2 text-sm text-zinc-500">{paidLabel} · {usd(amount)}</span>
          {invoiceRefs && <span className="ml-2 text-xs text-zinc-400">inv {invoiceRefs}</span>}
        </div>
        <div className="flex items-center gap-3 text-sm">
          <span className={`rounded px-2 py-0.5 text-xs font-medium ring-1 ${exhausted ? 'bg-zinc-100 text-zinc-500 ring-zinc-300' : 'bg-green-50 text-green-700 ring-green-300'}`}>
            {balance} of {totalServices} left
          </span>
          <button onClick={toggle} className="text-xs text-blue-600 hover:underline">{open ? 'Hide' : 'Review draw-downs'}</button>
          <button onClick={removeAll} className="text-xs text-red-500 hover:text-red-700">Delete</button>
        </div>
      </div>

      {open && (
        <div className="border-t border-zinc-200 p-4">
          {error && <p className="mb-2 text-sm text-red-600">{error}</p>}

          {redemptions.length > 0 && (
            <div className="mb-4">
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Confirmed</h3>
              <ul className="flex flex-col gap-1 text-sm">
                {redemptions.map((r) => (
                  <li key={r.id} className="flex items-center justify-between gap-3">
                    <span>{r.serviceDate} · {r.serviceName ?? r.serviceVariationId} · {r.providerName} · {usd(r.menuPrice)}{r.counts ? '' : ' (add-on)'}</span>
                    <button onClick={() => undo(r.id)} className="text-xs text-red-500 hover:text-red-700">Undo</button>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="mb-1 flex items-center justify-between gap-3">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Candidate visits</h3>
            {customerId && !loading && !exhausted && candidates && candidates.length > 0 && (
              <button onClick={confirmSelected} disabled={picked.size === 0 || confirming}
                className="rounded bg-green-600 px-3 py-1 text-xs font-medium text-white hover:bg-green-700 disabled:opacity-50">
                {confirming ? 'Confirming…' : `Confirm selected${picked.size ? ` (${picked.size})` : ''}`}
              </button>
            )}
          </div>
          {!customerId && <p className="text-sm text-amber-600">Link a Square customer to look up their bookings.</p>}
          {customerId && loading && <p className="text-sm text-zinc-400">Loading bookings…</p>}
          {customerId && !loading && candidates && candidates.length === 0 && (
            <p className="text-sm text-zinc-400">No un-redeemed bookings for this customer since the paid date.</p>
          )}
          {customerId && !loading && candidates && candidates.length > 0 && (
            exhausted ? (
              <p className="text-sm text-zinc-500">No credit left — further visits need payment.</p>
            ) : (
              <ul className="flex flex-col gap-0.5 text-sm">
                {candidates.map((c) => (
                  <li key={`${c.bookingId}-${c.serviceVariationId}`} className={`rounded px-1 ${picked.has(candKey(c)) ? 'bg-green-50' : ''}`}>
                    <label className="flex cursor-pointer items-center gap-2.5 py-1">
                      <input type="checkbox" checked={picked.has(candKey(c))} onChange={() => togglePick(c)} className="h-4 w-4" />
                      <span>{c.date}{c.time ? ` · ${c.time}` : ''} · {c.serviceName} · {c.providerName} · {usd(c.menuPrice)}{c.counts ? '' : ' (add-on)'}</span>
                    </label>
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
