'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import type { CustomerMatch, OwnerCustomer } from '../../lib/types';

export default function OwnerCustomerManager({
  initialCustomers,
}: {
  initialCustomers: OwnerCustomer[];
}) {
  const router = useRouter();
  const [customers, setCustomers] = useState(initialCustomers);
  const [error, setError] = useState('');

  // add picker
  const [query, setQuery] = useState('');
  const [matches, setMatches] = useState<CustomerMatch[]>([]);
  const [searching, setSearching] = useState(false);
  const [searched, setSearched] = useState(false);
  const [busy, setBusy] = useState(false);

  async function refresh() {
    setCustomers(await fetch('/api/owner-customers', { cache: 'no-store' }).then((r) => r.json()));
    router.refresh();
  }

  async function search(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (!query.trim()) return;
    setSearching(true);
    setSearched(false);
    try {
      const res = await fetch(`/api/owner-customers/search?q=${encodeURIComponent(query.trim())}`, {
        cache: 'no-store',
      });
      if (!res.ok) throw new Error((await res.json())?.error ?? 'Search failed');
      setMatches(await res.json());
      setSearched(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed');
    } finally {
      setSearching(false);
    }
  }

  async function add(squareCustomerId: string, label: string) {
    setError('');
    setBusy(true);
    try {
      const res = await fetch('/api/owner-customers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ squareCustomerId, label }),
      });
      if (!res.ok) {
        const msg = res.status === 409 ? 'Already marked as an owner customer.' : 'Could not add.';
        throw new Error(msg);
      }
      setQuery('');
      setMatches([]);
      setSearched(false);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add.');
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    setError('');
    const res = await fetch(`/api/owner-customers/${id}`, { method: 'DELETE' });
    if (!res.ok && res.status !== 204) {
      setError('Could not remove.');
      return;
    }
    await refresh();
  }

  const existing = new Set(customers.map((c) => c.squareCustomerId));

  return (
    <div className="space-y-8">
      {error && (
        <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-red-200">{error}</p>
      )}

      {/* Add a customer */}
      <section>
        <h2 className="mb-2 text-sm font-semibold">Add an owner customer</h2>
        <form onSubmit={search} data-testid="owner-customer-search-form" className="flex gap-2">
          <input
            data-testid="owner-customer-search-input"
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search Square customers by name…"
            className="flex-1 rounded-md px-3 py-2 text-sm ring-1 ring-zinc-300 focus:outline-none focus:ring-2 focus:ring-zinc-500"
          />
          <button
            data-testid="owner-customer-search-btn"
            type="submit"
            disabled={searching || !query.trim()}
            className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {searching ? 'Searching…' : 'Search'}
          </button>
        </form>

        {searched && matches.length === 0 && (
          <p className="mt-3 text-sm text-zinc-500">
            No matches in the first few pages of customers. Refine the name, or paste the Square customer
            id if you have it.
          </p>
        )}
        {matches.length > 0 && (
          <ul className="mt-3 divide-y divide-zinc-100 rounded-lg ring-1 ring-zinc-200">
            {matches.map((m) => (
              <li key={m.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                <span>
                  {m.name || '(no name)'}
                  <span className="ml-2 text-xs text-zinc-400">{m.id}</span>
                </span>
                {existing.has(m.id) ? (
                  <span className="text-xs text-zinc-400">already added</span>
                ) : (
                  <button
                    data-testid={`owner-customer-add-${m.id}`}
                    onClick={() => add(m.id, m.name)}
                    disabled={busy}
                    className="rounded bg-zinc-100 px-2.5 py-1 text-xs font-medium text-zinc-700 ring-1 ring-zinc-300 hover:bg-zinc-200 disabled:opacity-50"
                  >
                    Mark as owner
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Current list */}
      <section>
        <h2 className="mb-2 text-sm font-semibold">Owner customers</h2>
        {customers.length === 0 ? (
          <p className="rounded-lg p-4 text-center text-sm text-zinc-400 ring-1 ring-zinc-200">
            None yet. Search above to mark a customer as owner/family.
          </p>
        ) : (
          <ul data-testid="owner-customer-list" className="divide-y divide-zinc-100 rounded-lg ring-1 ring-zinc-200">
            {customers.map((c) => (
              <li key={c.id} data-testid={`owner-customer-row-${c.id}`} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                <span>
                  {c.name || '(name unavailable)'}
                  <span className="ml-2 text-xs text-zinc-400">{c.squareCustomerId}</span>
                </span>
                <button
                  data-testid={`owner-customer-remove-${c.id}`}
                  onClick={() => remove(c.id)}
                  className="text-xs text-zinc-400 hover:text-red-600"
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
