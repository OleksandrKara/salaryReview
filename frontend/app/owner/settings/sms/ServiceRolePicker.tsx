'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { CatalogSearchResultDto, ServiceLifecycleRoleDto } from '../../../lib/types';

// One role's worth of "which Square service(s) count as this" — a list plus a type-to-search
// picker for adding more, scoped to a single fixed role (the caller decides which). Deliberately
// no free-text id field: the owner searches their own live Square catalog and picks a real result,
// so the id that actually gets stored always comes from Square itself, never hand-typed — see
// ServiceLifecycleRole's own doc for why a hand-copied id is easy to get subtly wrong (an item id
// where a variation id belongs looks the same to a human, but never matches anything downstream).
export default function ServiceRolePicker({
  role,
  entries,
  onChange,
}: {
  role: string;
  entries: ServiceLifecycleRoleDto[];
  onChange: (next: ServiceLifecycleRoleDto[]) => void;
}) {
  const [adding, setAdding] = useState(false);

  async function handleDelete(id: number) {
    const prev = entries;
    onChange(entries.filter((e) => e.id !== id));
    try {
      await api.deleteServiceLifecycleRole(id);
    } catch (err) {
      onChange(prev);
      alert(err instanceof Error ? err.message : 'Failed to remove');
    }
  }

  return (
    <div>
      {entries.length === 0 && !adding && (
        <p className="mb-2 text-xs text-amber-600">Not configured yet — this automation stays off for everyone until it is.</p>
      )}
      {entries.length > 0 && (
        <div className="mb-2 flex flex-col gap-1.5">
          {entries.map((e) => (
            <div key={e.id} className="flex items-center justify-between gap-2 rounded-md bg-zinc-50 px-3 py-1.5">
              <span className="text-sm text-zinc-700">{e.displayName}</span>
              <button
                type="button"
                onClick={() => handleDelete(e.id)}
                className="text-xs font-medium text-zinc-400 hover:text-red-600"
              >
                Remove
              </button>
            </div>
          ))}
        </div>
      )}

      {adding ? (
        <SearchAndAdd
          role={role}
          onAdded={(created) => {
            onChange([...entries, created]);
            setAdding(false);
          }}
          onCancel={() => setAdding(false)}
        />
      ) : (
        <button
          type="button"
          onClick={() => setAdding(true)}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-2.5 py-1 text-xs font-medium text-white"
        >
          + Add service
        </button>
      )}
    </div>
  );
}

function SearchAndAdd({
  role,
  onAdded,
  onCancel,
}: {
  role: string;
  onAdded: (r: ServiceLifecycleRoleDto) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<CatalogSearchResultDto[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<CatalogSearchResultDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    // Every setState call deferred into the timeout callback, none synchronous in the effect body
    // — avoids cascading renders (see MessagesView.tsx's identical debounce convention).
    const handle = setTimeout(async () => {
      if (selected || query.trim().length < 2) {
        setResults(null);
        return;
      }
      setSearching(true);
      try {
        const r = await api.searchServiceLifecycleRoleCatalog(query.trim());
        setResults(r);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, 300);
    return () => clearTimeout(handle);
  }, [query, selected]);

  async function save() {
    if (!selected) {
      setError('Search for the service and pick it from the list');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const created = await api.createServiceLifecycleRole(role, selected.variationId);
      onAdded(created);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-zinc-200 bg-white p-2.5">
      {selected ? (
        <div className="flex items-center justify-between gap-2 rounded border border-zinc-300 bg-zinc-50 px-2 py-1.5 text-sm">
          <span>{selected.displayName}</span>
          <span className="flex items-center gap-2">
            <a href={selected.dashboardUrl} target="_blank" rel="noopener noreferrer" title="Open in Square" className="text-xs text-zinc-400 hover:text-zinc-700">
              ↗
            </a>
            <button type="button" onClick={() => { setSelected(null); setQuery(''); }} className="text-xs text-zinc-400 hover:text-zinc-700">
              Change
            </button>
          </span>
        </div>
      ) : (
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search your Square services…"
          autoFocus
          className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
        />
      )}

      {!selected && query.trim().length >= 2 && (
        <div className="max-h-40 overflow-y-auto rounded border border-zinc-200">
          {searching && (
            <div className="flex items-center gap-2 px-3 py-2 text-xs text-zinc-400">
              <Spinner className="h-3 w-3" /> Searching…
            </div>
          )}
          {!searching && results && results.length === 0 && (
            <div className="px-3 py-2 text-xs text-zinc-400">No matching services found</div>
          )}
          {!searching && results?.map((r) => (
            <div key={r.variationId} className="flex items-center justify-between gap-2 px-1 hover:bg-zinc-50">
              <button
                type="button"
                onClick={() => { setSelected(r); setResults(null); }}
                className="flex-1 py-2 pl-2 text-left text-sm"
              >
                {r.displayName}
              </button>
              {/* Opens the real item in Square's own dashboard so the owner can tell apart a
                  same-named duplicate before picking — found live 2026-08-25, an owner spotted
                  two identically-named results with no way to tell which was real. */}
              <a
                href={r.dashboardUrl}
                target="_blank"
                rel="noopener noreferrer"
                onClick={(e) => e.stopPropagation()}
                title="Open in Square"
                className="shrink-0 px-2 py-2 text-xs text-zinc-400 hover:text-zinc-700"
              >
                ↗
              </a>
            </div>
          ))}
        </div>
      )}

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={save}
          disabled={saving}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-2.5 py-1 text-xs font-medium text-white disabled:opacity-40"
        >
          {saving && <Spinner className="h-3 w-3" />}
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" onClick={onCancel} className="text-xs text-zinc-500 hover:text-zinc-800">
          Cancel
        </button>
      </div>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
