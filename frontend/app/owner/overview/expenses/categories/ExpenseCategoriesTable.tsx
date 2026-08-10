'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../../lib/api';
import type { ExpenseCategoryDefinition } from '../../../../lib/types';

function CategoryRow({ category, onChanged }: { category: ExpenseCategoryDefinition; onChanged: () => void }) {
  const [editing, setEditing] = useState(false);
  const [label, setLabel] = useState(category.label);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function save() {
    setBusy(true);
    setError('');
    try {
      await api.renameExpenseCategory(category.id, label);
      setEditing(false);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save.');
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!window.confirm(`Delete the "${category.label}" category?`)) return;
    setBusy(true);
    setError('');
    try {
      await api.deleteExpenseCategory(category.id);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete.');
      setBusy(false);
    }
  }

  async function togglePersonal(next: boolean) {
    setBusy(true);
    setError('');
    try {
      await api.setExpenseCategoryPersonal(category.id, next);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-lg p-3 ring-1 ring-zinc-200">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          {editing ? (
            <input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              className="w-48 rounded border border-zinc-300 px-1.5 py-1 text-sm"
            />
          ) : (
            <span className="text-sm font-medium text-zinc-800">{category.label}</span>
          )}
          {category.locked && (
            <span className="ml-1.5 rounded-full bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 ring-1 ring-inset ring-zinc-200">
              Built in
            </span>
          )}
          {category.isPersonal && (
            <span className="ml-1.5 rounded-full bg-violet-50 px-1.5 py-0.5 text-[10px] font-medium text-violet-700 ring-1 ring-inset ring-violet-200">
              Personal
            </span>
          )}
          <div className="text-xs text-zinc-400">{category.code}</div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {!category.locked && (
            <label className="flex items-center gap-1.5 text-xs text-zinc-600">
              <input
                type="checkbox"
                checked={category.isPersonal}
                disabled={busy}
                onChange={(e) => togglePersonal(e.target.checked)}
                className="h-3.5 w-3.5 rounded border-zinc-300"
              />
              Personal
            </label>
          )}
          {editing ? (
            <>
              <button type="button" disabled={busy} onClick={save} className="rounded bg-zinc-800 px-2 py-1.5 text-xs font-medium text-white hover:bg-zinc-700 disabled:opacity-50">
                Save
              </button>
              <button type="button" disabled={busy} onClick={() => { setEditing(false); setLabel(category.label); }} className="-m-1 p-1 text-xs text-zinc-500 hover:underline">
                Cancel
              </button>
            </>
          ) : (
            <>
              <button type="button" onClick={() => setEditing(true)} className="-m-1 p-1 text-xs text-blue-600 hover:underline">Rename</button>
              {!category.locked && (
                <button type="button" disabled={busy} onClick={remove} className="-m-1 p-1 text-xs text-red-600 hover:underline disabled:opacity-50">
                  Delete
                </button>
              )}
            </>
          )}
        </div>
      </div>
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
    </div>
  );
}

export default function ExpenseCategoriesTable() {
  const [categories, setCategories] = useState<ExpenseCategoryDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [creating, setCreating] = useState(false);

  async function load() {
    try {
      setCategories(await api.listExpenseCategories());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load categories.');
    } finally {
      setLoading(false);
    }
  }

  // Initial fetch on mount — a plain .then() chain, not the reusable `load` above (called from
  // event handlers after a mutation), so state updates happen as a subscription callback rather
  // than synchronously inside the effect body.
  useEffect(() => {
    let cancelled = false;
    api.listExpenseCategories()
      .then((result) => { if (!cancelled) setCategories(result); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load categories.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  async function create() {
    const trimmed = newLabel.trim();
    if (!trimmed) return;
    setCreating(true);
    setError('');
    try {
      await api.createExpenseCategory(trimmed);
      setNewLabel('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create category.');
    } finally {
      setCreating(false);
    }
  }

  if (loading) return <p className="mt-6 text-sm text-zinc-500">Loading…</p>;

  return (
    <div className="mt-4 flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={newLabel}
          onChange={(e) => setNewLabel(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void create(); } }}
          placeholder="New category name…"
          className="w-full rounded border border-zinc-300 px-2 py-1.5 text-sm sm:w-56"
        />
        <button
          type="button"
          disabled={creating || !newLabel.trim()}
          onClick={create}
          className="rounded bg-zinc-800 px-3 py-2 text-xs font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          {creating ? 'Adding…' : 'Add category'}
        </button>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex flex-col gap-2">
        {categories.map((c) => (
          <CategoryRow key={c.id} category={c} onChanged={load} />
        ))}
      </div>
    </div>
  );
}
