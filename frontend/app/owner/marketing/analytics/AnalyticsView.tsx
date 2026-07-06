'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import type { MarketingAnalyticsData } from '../../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

function monthToDateRange(): { from: string; to: string } {
  const now = new Date();
  const from = new Date(now.getFullYear(), now.getMonth(), 1);
  return { from: isoDate(from), to: isoDate(now) };
}

function lastNDaysRange(n: number): { from: string; to: string } {
  const now = new Date();
  const from = new Date(now);
  from.setDate(from.getDate() - (n - 1));
  return { from: isoDate(from), to: isoDate(now) };
}

type PresetKey = 'mtd' | '7d' | '30d' | 'custom';

export default function AnalyticsView({ initialData }: { initialData: MarketingAnalyticsData }) {
  const [data, setData] = useState(initialData);
  const [preset, setPreset] = useState<PresetKey>('mtd');
  const [from, setFrom] = useState(initialData.from);
  const [to, setTo] = useState(initialData.to);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function load(nextFrom: string, nextTo: string) {
    setLoading(true);
    setError('');
    try {
      const result = await api.getMarketingAnalytics(nextFrom, nextTo);
      setData(result);
      setFrom(result.from);
      setTo(result.to);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load analytics.');
    } finally {
      setLoading(false);
    }
  }

  function selectPreset(key: PresetKey) {
    setPreset(key);
    const range = key === 'mtd' ? monthToDateRange() : key === '7d' ? lastNDaysRange(7) : lastNDaysRange(30);
    void load(range.from, range.to);
  }

  function applyCustomRange() {
    setPreset('custom');
    void load(from, to);
  }

  return (
    <div>
      <div className="flex flex-wrap gap-2">
        <PresetButton label="Month to date" active={preset === 'mtd'} onClick={() => selectPreset('mtd')} />
        <PresetButton label="Last 7 days" active={preset === '7d'} onClick={() => selectPreset('7d')} />
        <PresetButton label="Last 30 days" active={preset === '30d'} onClick={() => selectPreset('30d')} />
      </div>

      <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">From</span>
          <input
            type="date"
            value={from}
            max={to}
            onChange={(e) => setFrom(e.target.value)}
            className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">To</span>
          <input
            type="date"
            value={to}
            min={from}
            max={isoDate(new Date())}
            onChange={(e) => setTo(e.target.value)}
            className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
          />
        </label>
        <button
          type="button"
          onClick={applyCustomRange}
          className="rounded bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
        >
          Apply
        </button>
      </div>

      {error ? <p className="mt-3 text-sm text-red-600">{error}</p> : null}

      <p className="mt-4 text-xs text-zinc-500">
        {data.from} to {data.to}
        {loading ? ' · loading…' : ''}
      </p>

      <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Customers" value={data.customerCount.toLocaleString()} />
        <StatCard label="Services" value={data.serviceCount.toLocaleString()} />
        <StatCard label="Gross revenue" value={usd(data.grossRevenue)} />
      </div>
    </div>
  );
}

function PresetButton({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        active
          ? 'rounded-full bg-blue-600 px-3 py-1.5 text-xs font-medium text-white'
          : 'rounded-full bg-zinc-100 px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-200'
      }
    >
      {label}
    </button>
  );
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</div>
      <div className="mt-1 text-2xl font-semibold text-zinc-900">{value}</div>
    </div>
  );
}
