'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { TrackingSiteDto } from '../../../lib/types';

// One save button per site rather than a single page-wide submit: the sites are independent (a
// business owns 1-2), the backend PUT is already per-hostname (see TrackingSettingsController),
// and per-row save/error state is clearer than one button covering several unrelated saves.
export default function TrackingSettingsForm({ initialSites }: { initialSites: TrackingSiteDto[] }) {
  return (
    <div className="mt-6 flex flex-col gap-4">
      {initialSites.map((site) => (
        <SiteRow key={site.hostname} site={site} />
      ))}
    </div>
  );
}

function SiteRow({ site }: { site: TrackingSiteDto }) {
  const [value, setValue] = useState(site.clarityProjectId ?? '');
  const [savedAt, setSavedAt] = useState(site.updatedAt);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateTrackingConfig(site.hostname, value.trim());
      setValue(updated.clarityProjectId ?? '');
      setSavedAt(updated.updatedAt);
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="flex flex-col gap-2 rounded-lg ring-1 ring-zinc-200 p-4">
      <label className="text-sm">
        <span className="mb-1 block font-medium text-zinc-800">{site.siteLabel}</span>
        <span className="mb-1 block text-xs text-zinc-500">{site.hostname}</span>
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="Clarity project id, e.g. abc123def4"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          className="inline-flex items-center gap-2 rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {saving && <Spinner className="h-4 w-4" />}
          {saving ? 'Saving…' : 'Save'}
        </button>
        {saved && <span className="text-sm text-green-700">Saved.</span>}
        {!saved && savedAt && (
          <span className="text-xs text-zinc-400">Last updated {new Date(savedAt).toLocaleString()}</span>
        )}
      </div>
    </form>
  );
}
