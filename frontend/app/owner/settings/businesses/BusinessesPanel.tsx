'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { PlatformBusinessDto } from '../../../lib/types';

export default function BusinessesPanel({ initialBusinesses }: { initialBusinesses: PlatformBusinessDto[] }) {
  const [businesses, setBusinesses] = useState(initialBusinesses);
  const [name, setName] = useState('');
  const [shortCode, setShortCode] = useState('');
  const [timezone, setTimezone] = useState('America/Los_Angeles');
  const [ownerUsername, setOwnerUsername] = useState('');
  const [ownerPassword, setOwnerPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      const created = await api.createBusiness({ name, shortCode, timezone, ownerUsername, ownerPassword });
      setBusinesses((prev) => [...prev, created]);
      setName('');
      setShortCode('');
      setOwnerUsername('');
      setOwnerPassword('');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create business');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mt-6 flex flex-col gap-6">
      <div className="rounded-lg ring-1 ring-zinc-200 p-4">
        <h2 className="text-sm font-medium text-zinc-700">Existing businesses</h2>
        <ul className="mt-3 flex flex-col gap-2">
          {businesses.map((b) => (
            <li key={b.id} className="flex items-center justify-between text-sm">
              <span>
                {b.name} <span className="text-zinc-400">({b.shortCode})</span>
              </span>
              <span className="text-zinc-400">{b.timezone}</span>
            </li>
          ))}
        </ul>
      </div>

      <form onSubmit={create} className="flex flex-col gap-4 rounded-lg ring-1 ring-zinc-200 p-4">
        <h2 className="text-sm font-medium text-zinc-700">Add a new business</h2>

        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Name</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. AK PMU"
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
        </label>

        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Short code</span>
          <input
            value={shortCode}
            onChange={(e) => setShortCode(e.target.value)}
            placeholder="e.g. annakarapmu"
            autoComplete="off"
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
        </label>

        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Timezone</span>
          <input
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
        </label>

        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">First owner username</span>
          <input
            value={ownerUsername}
            onChange={(e) => setOwnerUsername(e.target.value)}
            autoComplete="off"
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
        </label>

        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">First owner password</span>
          <input
            type="password"
            value={ownerPassword}
            onChange={(e) => setOwnerPassword(e.target.value)}
            autoComplete="new-password"
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
        </label>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div>
          <button
            type="submit"
            disabled={saving}
            className="inline-flex items-center gap-2 rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {saving && <Spinner className="h-4 w-4" />}
            {saving ? 'Creating…' : 'Create business'}
          </button>
        </div>
      </form>
    </div>
  );
}
