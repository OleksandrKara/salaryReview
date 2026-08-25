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
          excludeVariationIds={entries.map((e) => e.squareVariationId)}
          // Only merges into the saved list — does NOT close the form itself, so a partial
          // failure (see SearchAndAdd.save) leaves the form open with its error and the still-
          // failed items staged for retry, rather than silently discarding them on close.
          onAddedMany={(created) => onChange([...entries, ...created])}
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
  excludeVariationIds,
  onAddedMany,
  onCancel,
}: {
  role: string;
  excludeVariationIds: string[];
  onAddedMany: (created: ServiceLifecycleRoleDto[]) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<CatalogSearchResultDto[] | null>(null);
  const [searching, setSearching] = useState(false);
  // Staged, not-yet-saved picks — can hold several at once so one search-and-check pass adds
  // multiple services in one "Save" (see class doc / direct request).
  const [staged, setStaged] = useState<CatalogSearchResultDto[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    // Every setState call deferred into the timeout callback, none synchronous in the effect body
    // — avoids cascading renders (see MessagesView.tsx's identical debounce convention).
    const handle = setTimeout(async () => {
      if (query.trim().length < 2) {
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
  }, [query]);

  // Never re-offer a service already configured for this role, or one already checked off in this
  // same add pass — both would just 409 (or double-add) on save.
  const stagedIds = new Set(staged.map((s) => s.variationId));
  const excludeIds = new Set(excludeVariationIds);
  const visibleResults = results?.filter((r) => !stagedIds.has(r.variationId) && !excludeIds.has(r.variationId));

  function toggleStaged(r: CatalogSearchResultDto) {
    setStaged((prev) => (prev.some((s) => s.variationId === r.variationId) ? prev : [...prev, r]));
  }

  function unstage(variationId: string) {
    setStaged((prev) => prev.filter((s) => s.variationId !== variationId));
  }

  async function save() {
    if (staged.length === 0) {
      setError('Search for a service and check it off the list');
      return;
    }
    setSaving(true);
    setError('');
    const created: ServiceLifecycleRoleDto[] = [];
    const failed: CatalogSearchResultDto[] = [];
    // Sequential, not Promise.all — these all write the same table; keeping it simple and
    // predictable (and easy to reason about partial failure) matters more than shaving off the
    // few hundred ms a handful of sequential saves take.
    for (const item of staged) {
      try {
        created.push(await api.createServiceLifecycleRole(role, item.variationId));
      } catch {
        failed.push(item);
      }
    }
    setSaving(false);
    if (created.length > 0) {
      onAddedMany(created);
    }
    if (failed.length === 0) {
      onCancel(); // everything saved — close the form, same as a single-item save always did
      return;
    }
    // Partial failure: stay open, keep only the failed ones staged so the owner can retry without
    // re-searching, and don't let the parent close this form out from under that state.
    setError(`Failed to save: ${failed.map((f) => f.displayName).join(', ')}`);
    setStaged(failed);
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-zinc-200 bg-white p-2.5">
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search your Square services…"
        autoFocus
        className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
      />

      {staged.length > 0 && (
        <div className="flex flex-col gap-1">
          {staged.map((s) => (
            <div key={s.variationId} className="flex items-center justify-between gap-2 rounded border border-zinc-300 bg-zinc-50 px-2 py-1.5 text-sm">
              <span>✓ {s.displayName}</span>
              <span className="flex items-center gap-2">
                <a href={s.dashboardUrl} target="_blank" rel="noopener noreferrer" title="Open in Square" className="text-xs text-zinc-400 hover:text-zinc-700">
                  ↗
                </a>
                <button type="button" onClick={() => unstage(s.variationId)} className="text-xs text-zinc-400 hover:text-zinc-700">
                  Remove
                </button>
              </span>
            </div>
          ))}
        </div>
      )}

      {query.trim().length >= 2 && (
        <div className="max-h-40 overflow-y-auto rounded border border-zinc-200">
          {searching && (
            <div className="flex items-center gap-2 px-3 py-2 text-xs text-zinc-400">
              <Spinner className="h-3 w-3" /> Searching…
            </div>
          )}
          {!searching && visibleResults && visibleResults.length === 0 && (
            <div className="px-3 py-2 text-xs text-zinc-400">
              {results && results.length > 0 ? 'All matches already added' : 'No matching services found'}
            </div>
          )}
          {!searching && visibleResults?.map((r) => (
            <div key={r.variationId} className="flex items-center justify-between gap-2 px-1 hover:bg-zinc-50">
              <button
                type="button"
                onClick={() => toggleStaged(r)}
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
          disabled={saving || staged.length === 0}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-2.5 py-1 text-xs font-medium text-white disabled:opacity-40"
        >
          {saving && <Spinner className="h-3 w-3" />}
          {saving ? 'Saving…' : staged.length > 1 ? `Save ${staged.length} services` : 'Save'}
        </button>
        <button type="button" onClick={onCancel} className="text-xs text-zinc-500 hover:text-zinc-800">
          {staged.length > 0 ? 'Cancel' : 'Close'}
        </button>
      </div>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
