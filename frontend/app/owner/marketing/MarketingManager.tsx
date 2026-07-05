'use client';

import { useState } from 'react';
import { api } from '../../lib/api';
import type { MarketingVariantStat } from '../../lib/types';
import VariantTable from './VariantTable';

// datetime-local inputs use the browser's local timezone with no offset in the string;
// new Date(...)/.toISOString() round-trip through UTC correctly for both directions.
function toDatetimeLocalValue(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function MarketingManager({
  slug,
  initialVariants,
  initialStatsSince,
}: {
  slug: string;
  initialVariants: MarketingVariantStat[];
  initialStatsSince: string | null;
}) {
  const [variants, setVariants] = useState(initialVariants);
  const [statsSince, setStatsSince] = useState(initialStatsSince);
  const [cutoffInput, setCutoffInput] = useState(initialStatsSince ? toDatetimeLocalValue(initialStatsSince) : '');
  const [error, setError] = useState('');
  const [busyVariantId, setBusyVariantId] = useState<string | null>(null);
  const [cutoffBusy, setCutoffBusy] = useState(false);

  async function refresh() {
    const data = await api.getMarketingDashboard(slug);
    setVariants(data.variants);
    setStatsSince(data.statsSince);
  }

  async function withVariantBusy(v: MarketingVariantStat, action: () => Promise<void>) {
    setError('');
    setBusyVariantId(v.variantId);
    try {
      await action();
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setBusyVariantId(null);
    }
  }

  function onToggleActive(v: MarketingVariantStat) {
    return withVariantBusy(v, () => api.setMarketingVariantActive(v.variantId, !v.active));
  }

  function onRename(v: MarketingVariantStat) {
    const name = prompt('New name for this variant:', v.name);
    if (!name || !name.trim() || name.trim() === v.name) return;
    return withVariantBusy(v, () => api.renameMarketingVariant(v.variantId, name.trim()));
  }

  function onDuplicate(v: MarketingVariantStat) {
    const name = prompt('Name for the copy:', `${v.name} (copy)`);
    if (!name || !name.trim()) return;
    return withVariantBusy(v, () => api.duplicateMarketingVariant(v.variantId, name.trim()).then(() => undefined));
  }

  function onDelete(v: MarketingVariantStat) {
    if (v.pageViews > 0 || v.bookingsCompleted > 0) {
      if (!confirm(`"${v.name}" has ${v.pageViews} page view(s) and ${v.bookingsCompleted} booking(s) recorded — deletion will likely be blocked. Try anyway?`)) return;
    } else if (!confirm(`Delete "${v.name}"? This cannot be undone.`)) {
      return;
    }
    return withVariantBusy(v, () => api.deleteMarketingVariant(v.variantId));
  }

  async function saveCutoff() {
    setError('');
    setCutoffBusy(true);
    try {
      const iso = cutoffInput ? new Date(cutoffInput).toISOString() : null;
      await api.setMarketingStatsSince(slug, iso);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save cutoff');
    } finally {
      setCutoffBusy(false);
    }
  }

  async function clearCutoff() {
    setError('');
    setCutoffBusy(true);
    try {
      await api.setMarketingStatsSince(slug, null);
      setCutoffInput('');
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to clear cutoff');
    } finally {
      setCutoffBusy(false);
    }
  }

  return (
    <div>
      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-red-200">{error}</p>}

      <div className="mb-6 flex flex-wrap items-end gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
        <label className="text-sm">
          <span className="mb-1 block text-xs font-medium text-zinc-500">Hide stats before</span>
          <input
            type="datetime-local"
            value={cutoffInput}
            onChange={(e) => setCutoffInput(e.target.value)}
            className="rounded border border-zinc-300 px-2 py-1 text-sm"
          />
        </label>
        <button
          type="button"
          disabled={cutoffBusy || !cutoffInput}
          onClick={saveCutoff}
          className="rounded bg-zinc-800 px-3 py-1.5 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          Save cutoff
        </button>
        {statsSince && (
          <button type="button" disabled={cutoffBusy} onClick={clearCutoff} className="rounded px-3 py-1.5 text-sm font-medium text-zinc-600 ring-1 ring-zinc-200 hover:bg-zinc-50">
            Clear
          </button>
        )}
        <span className="text-xs text-zinc-500">
          {statsSince ? `Showing stats since ${new Date(statsSince).toLocaleString()} — earlier activity (e.g. your own test traffic) is excluded from the numbers below.` : 'No cutoff set — showing all-time stats.'}
        </span>
      </div>

      <VariantTable
        variants={variants}
        onToggleActive={onToggleActive}
        onRename={onRename}
        onDuplicate={onDuplicate}
        onDelete={onDelete}
        busyVariantId={busyVariantId}
      />
    </div>
  );
}
