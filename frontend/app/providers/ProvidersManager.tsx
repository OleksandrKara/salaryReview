'use client';

import { useState } from 'react';
import { api } from '../lib/api';
import type { Provider } from '../lib/types';

export default function ProvidersManager({
  initialProviders,
}: {
  initialProviders: Provider[];
}) {
  const [providers, setProviders] = useState<Provider[]>(initialProviders);
  const [error, setError] = useState<string | null>(null);

  // create form state
  const [newName, setNewName] = useState('');
  const [newDisplay, setNewDisplay] = useState('');
  const [newRatePct, setNewRatePct] = useState('45');
  const [newFeePct, setNewFeePct] = useState('3.5');
  const [creating, setCreating] = useState(false);

  // per-row saving spinner
  const [savingId, setSavingId] = useState<number | null>(null);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!newName.trim() || !newDisplay.trim()) {
      setError('Name and display name are required.');
      return;
    }
    setCreating(true);
    try {
      const created = await api.createProvider({
        name: newName.trim(),
        displayName: newDisplay.trim(),
        commissionRate: newRatePct === '' ? null : Number(newRatePct) / 100,
        cardTipFeeRate: newFeePct === '' ? null : Number(newFeePct) / 100,
      });
      setProviders((prev) =>
        [...prev, created].sort((a, b) => a.displayName.localeCompare(b.displayName)),
      );
      setNewName('');
      setNewDisplay('');
      setNewRatePct('45');
      setNewFeePct('3.5');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setCreating(false);
    }
  }

  async function patch(id: number, patchBody: Parameters<typeof api.patchProvider>[1]) {
    setSavingId(id);
    setError(null);
    try {
      const updated = await api.patchProvider(id, patchBody);
      setProviders((prev) => prev.map((p) => (p.id === id ? updated : p)));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSavingId(null);
    }
  }

  async function remove(id: number, displayName: string) {
    const ok = window.confirm(
      `Delete provider "${displayName}"?\n\n` +
        `⚠️  This will also DELETE every period entry that belongs to this provider — ` +
        `historical settlements will be lost.\n\n` +
        `If you just want them off the active roster, uncheck "Active" instead.\n\n` +
        `Proceed with hard delete?`,
    );
    if (!ok) return;
    setSavingId(id);
    setError(null);
    try {
      await api.deleteProvider(id);
      setProviders((prev) => prev.filter((p) => p.id !== id));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSavingId(null);
    }
  }

  return (
    <div className="space-y-8">
      {/* Add new */}
      <section>
        <h2 className="mb-3 text-lg font-medium">Add a provider</h2>
        <form onSubmit={handleCreate} data-testid="add-provider-form" className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col text-sm">
            <span className="text-zinc-600">Full name</span>
            <input
              type="text"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="Anna Lastname"
              data-testid="add-provider-name"
              className="rounded border border-zinc-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col text-sm">
            <span className="text-zinc-600">Display name</span>
            <input
              type="text"
              value={newDisplay}
              onChange={(e) => setNewDisplay(e.target.value)}
              placeholder="Anna"
              data-testid="add-provider-display"
              className="w-32 rounded border border-zinc-300 px-2 py-1"
            />
          </label>
          <label className="flex flex-col text-sm">
            <span className="text-zinc-600">Default rate %</span>
            <input
              type="number"
              step="0.5"
              min={0}
              max={100}
              value={newRatePct}
              onChange={(e) => setNewRatePct(e.target.value)}
              data-testid="add-provider-rate"
              className="w-24 rounded border border-zinc-300 px-2 py-1 text-right"
            />
          </label>
          <label className="flex flex-col text-sm">
            <span className="text-zinc-600">Tip fee %</span>
            <input
              type="number"
              step="0.1"
              min={0}
              max={100}
              value={newFeePct}
              onChange={(e) => setNewFeePct(e.target.value)}
              data-testid="add-provider-fee"
              className="w-20 rounded border border-zinc-300 px-2 py-1 text-right"
            />
          </label>
          <button
            type="submit"
            disabled={creating}
            data-testid="add-provider-submit"
            className="rounded bg-zinc-900 px-4 py-2 text-white hover:bg-zinc-700 disabled:opacity-50"
          >
            {creating ? 'Adding…' : 'Add'}
          </button>
        </form>
        {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      </section>

      {/* Existing list */}
      <section>
        <h2 className="mb-3 text-lg font-medium">All providers</h2>
        <div className="overflow-x-auto rounded border border-zinc-200">
          <table className="w-full border-collapse text-sm">
            <thead className="bg-zinc-50 text-left text-zinc-600">
              <tr>
                <th className="px-3 py-2">Display</th>
                <th className="px-3 py-2">Full name</th>
                <th className="px-3 py-2">Default rate %</th>
                <th className="px-3 py-2">Tip fee %</th>
                <th className="px-3 py-2">Active</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <ProviderRow
                  key={p.id}
                  provider={p}
                  saving={savingId === p.id}
                  onPatch={(body) => patch(p.id, body)}
                  onDelete={() => remove(p.id, p.displayName)}
                />
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function ProviderRow({
  provider,
  saving,
  onPatch,
  onDelete,
}: {
  provider: Provider;
  saving: boolean;
  onPatch: (body: Parameters<typeof api.patchProvider>[1]) => void;
  onDelete: () => void;
}) {
  const [displayName, setDisplayName] = useState(provider.displayName);
  const [name, setName] = useState(provider.name);
  const [rate, setRate] = useState(String((provider.commissionRate * 100).toFixed(1)));
  const [fee, setFee] = useState(String((provider.cardTipFeeRate * 100).toFixed(1)));

  return (
    <tr className={`border-t border-zinc-200 ${!provider.active ? 'opacity-50' : ''}`}>
      <td className="px-3 py-2">
        <input
          type="text"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          onBlur={() => {
            const v = displayName.trim();
            if (v && v !== provider.displayName) onPatch({ displayName: v });
          }}
          className="w-28 rounded border border-zinc-300 px-2 py-1 font-medium"
        />
        {saving && <span className="ml-2 text-xs text-zinc-400">saving…</span>}
      </td>
      <td className="px-3 py-2">
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onBlur={() => {
            const v = name.trim();
            if (v && v !== provider.name) onPatch({ name: v });
          }}
          className="w-48 rounded border border-zinc-300 px-2 py-1"
        />
      </td>
      <td className="px-3 py-2">
        <input
          type="number"
          step="0.5"
          min={0}
          max={100}
          value={rate}
          onChange={(e) => setRate(e.target.value)}
          onBlur={() => {
            const v = Number(rate) / 100;
            if (v !== provider.commissionRate) onPatch({ commissionRate: v });
          }}
          className="w-20 rounded border border-zinc-300 px-2 py-1 text-right"
        />
      </td>
      <td className="px-3 py-2">
        <input
          type="number"
          step="0.1"
          min={0}
          max={100}
          value={fee}
          onChange={(e) => setFee(e.target.value)}
          onBlur={() => {
            const v = Number(fee) / 100;
            if (v !== provider.cardTipFeeRate) onPatch({ cardTipFeeRate: v });
          }}
          className="w-20 rounded border border-zinc-300 px-2 py-1 text-right"
        />
      </td>
      <td className="px-3 py-2">
        <label className="inline-flex items-center gap-2">
          <input
            type="checkbox"
            checked={provider.active}
            onChange={(e) => onPatch({ active: e.target.checked })}
          />
          <span className="text-xs text-zinc-500">{provider.active ? 'active' : 'inactive'}</span>
        </label>
      </td>
      <td className="px-3 py-2 text-right">
        <button
          onClick={onDelete}
          title="Hard delete (also removes historical entries)"
          className="text-zinc-400 hover:text-red-600"
        >
          ✕
        </button>
      </td>
    </tr>
  );
}
