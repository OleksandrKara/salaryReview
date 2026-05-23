'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from './lib/api';
import type { Half } from './lib/types';

export default function CreatePeriodForm() {
  const router = useRouter();
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [half, setHalf] = useState<Half>('FIRST');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const created = await api.createPeriod({ year, month, half });
      router.push(`/periods/${created.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
      <label className="flex flex-col text-sm">
        <span className="text-zinc-600">Year</span>
        <input
          type="number"
          value={year}
          onChange={(e) => setYear(Number(e.target.value))}
          className="rounded border border-zinc-300 px-2 py-1"
        />
      </label>
      <label className="flex flex-col text-sm">
        <span className="text-zinc-600">Month</span>
        <input
          type="number"
          min={1}
          max={12}
          value={month}
          onChange={(e) => setMonth(Number(e.target.value))}
          className="w-20 rounded border border-zinc-300 px-2 py-1"
        />
      </label>
      <label className="flex flex-col text-sm">
        <span className="text-zinc-600">Half</span>
        <select
          value={half}
          onChange={(e) => setHalf(e.target.value as Half)}
          className="rounded border border-zinc-300 px-2 py-1"
        >
          <option value="FIRST">FIRST (1-15)</option>
          <option value="SECOND">SECOND (16-end)</option>
        </select>
      </label>
      <button
        type="submit"
        disabled={busy}
        className="rounded bg-zinc-900 px-4 py-2 text-white hover:bg-zinc-700 disabled:opacity-50"
      >
        {busy ? 'Creating…' : 'Create'}
      </button>
      {error && <p className="w-full text-sm text-red-600">{error}</p>}
    </form>
  );
}
