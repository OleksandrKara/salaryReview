'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { CatalogSearchResultDto, ServiceLifecycleRoleDto } from '../../../lib/types';

// Well-known role keys already used by this business — offered as one-click picks, but the role
// column itself is a plain string (see ServiceLifecycleRole's own doc): a future business, or a
// future automation, can introduce a new stage via "Custom…" with no code change on either side.
const KNOWN_ROLES = [
  { value: 'TOUCH_UP', label: 'Touch-up' },
  { value: 'COLOR_BOOSTER', label: 'Color booster' },
  { value: 'INITIAL_PROCEDURE', label: 'Initial procedure' },
  { value: 'CONSULTATION', label: 'Consultation' },
];

function roleLabel(role: string): string {
  return KNOWN_ROLES.find((r) => r.value === role)?.label ?? role;
}

// Owner-editable "which Square service counts as a touch-up / color booster / etc." mapping, used
// by (not-yet-built) lifecycle-reminder automations. Deliberately no free-text id field: the owner
// searches by service name and picks from their own live Square catalog, so the id that actually
// gets stored always comes from Square itself — a raw-id text field looks the same for "the right
// id" and "a similar-looking but never-matching id" (an item id instead of its variation id), which
// is exactly what went wrong the one time this was set up by hand instead of through this picker.
export default function ServiceLifecycleRolesPanel({ initialRoles }: { initialRoles: ServiceLifecycleRoleDto[] }) {
  const [roles, setRoles] = useState(initialRoles);
  const [adding, setAdding] = useState(false);

  const grouped = groupByRole(roles);

  async function handleDelete(id: number) {
    const prev = roles;
    setRoles((r) => r.filter((x) => x.id !== id));
    try {
      await api.deleteServiceLifecycleRole(id);
    } catch (err) {
      setRoles(prev); // revert — the delete didn't actually happen
      alert(err instanceof Error ? err.message : 'Failed to remove');
    }
  }

  return (
    <div className="mt-4">
      {roles.length === 0 && !adding && (
        <p className="mb-3 text-sm text-zinc-400">No services mapped yet.</p>
      )}

      {Object.entries(grouped).map(([role, entries]) => (
        <div key={role} className="mb-3">
          <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-zinc-500">{roleLabel(role)}</div>
          <div className="flex flex-col gap-1.5">
            {entries.map((r) => (
              <div key={r.id} className="flex items-center justify-between gap-2 rounded-md bg-zinc-50 px-3 py-2">
                <span className="text-sm text-zinc-700">{r.displayName}</span>
                <button
                  type="button"
                  onClick={() => handleDelete(r.id)}
                  className="text-xs font-medium text-zinc-400 hover:text-red-600"
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        </div>
      ))}

      {adding ? (
        <AddRoleForm
          onAdded={(created) => {
            setRoles((r) => [...r, created]);
            setAdding(false);
          }}
          onCancel={() => setAdding(false)}
        />
      ) : (
        <button
          type="button"
          onClick={() => setAdding(true)}
          className="mt-1 inline-flex items-center gap-1.5 rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white"
        >
          + Add service
        </button>
      )}
    </div>
  );
}

function groupByRole(roles: ServiceLifecycleRoleDto[]): Record<string, ServiceLifecycleRoleDto[]> {
  const out: Record<string, ServiceLifecycleRoleDto[]> = {};
  for (const r of roles) {
    (out[r.role] ??= []).push(r);
  }
  return out;
}

function AddRoleForm({ onAdded, onCancel }: { onAdded: (r: ServiceLifecycleRoleDto) => void; onCancel: () => void }) {
  const [role, setRole] = useState(KNOWN_ROLES[0].value);
  const [customRole, setCustomRole] = useState('');
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<CatalogSearchResultDto[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<CatalogSearchResultDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const effectiveRole = role === 'CUSTOM' ? customRole.trim().toUpperCase() : role;

  useEffect(() => {
    // Every setState call deferred into the timeout callback, none synchronous in the effect body
    // itself — same convention MessagesView.tsx's own debounce uses, to avoid cascading renders.
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
    if (!effectiveRole) {
      setError('Choose or type a role');
      return;
    }
    if (!selected) {
      setError('Search for the service and pick it from the list');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const created = await api.createServiceLifecycleRole(effectiveRole, selected.variationId);
      onAdded(created);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mt-2 flex flex-col gap-2.5 rounded-md border border-zinc-200 bg-white p-3">
      <label className="text-xs text-zinc-500">
        <span className="mb-1 block">Role</span>
        <select
          value={role}
          onChange={(e) => setRole(e.target.value)}
          className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
        >
          {KNOWN_ROLES.map((r) => (
            <option key={r.value} value={r.value}>{r.label}</option>
          ))}
          <option value="CUSTOM">Custom…</option>
        </select>
      </label>
      {role === 'CUSTOM' && (
        <input
          value={customRole}
          onChange={(e) => setCustomRole(e.target.value)}
          placeholder="e.g. LASH_REFILL"
          className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
        />
      )}

      <label className="text-xs text-zinc-500">
        <span className="mb-1 block">Service (search your Square catalog)</span>
        {selected ? (
          <div className="flex items-center justify-between gap-2 rounded border border-zinc-300 bg-zinc-50 px-2 py-1.5 text-sm">
            <span>{selected.displayName}</span>
            <button type="button" onClick={() => { setSelected(null); setQuery(''); }} className="text-xs text-zinc-400 hover:text-zinc-700">
              Change
            </button>
          </div>
        ) : (
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="e.g. touch-up, color booster…"
            className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
          />
        )}
      </label>

      {!selected && query.trim().length >= 2 && (
        <div className="max-h-48 overflow-y-auto rounded border border-zinc-200">
          {searching && (
            <div className="flex items-center gap-2 px-3 py-2 text-xs text-zinc-400">
              <Spinner className="h-3 w-3" /> Searching…
            </div>
          )}
          {!searching && results && results.length === 0 && (
            <div className="px-3 py-2 text-xs text-zinc-400">No matching services found</div>
          )}
          {!searching && results?.map((r) => (
            <button
              key={r.variationId}
              type="button"
              onClick={() => { setSelected(r); setResults(null); }}
              className="block w-full px-3 py-2 text-left text-sm hover:bg-zinc-50"
            >
              {r.displayName}
            </button>
          ))}
        </div>
      )}
      {!selected && query.trim().length > 0 && query.trim().length < 2 && (
        <p className="text-xs text-zinc-400">Keep typing — need at least 2 characters</p>
      )}

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={save}
          disabled={saving}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
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
