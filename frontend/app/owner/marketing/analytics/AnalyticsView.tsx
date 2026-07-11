'use client';

import { useMemo, useState } from 'react';
import { api } from '../../../lib/api';
import type {
  MarketingAnalyticsData,
  MarketingAnalyticsSegment,
  MarketingUpcomingAppointment,
  TrafficSourceKey,
} from '../../../lib/types';
import TrafficSourceFilter, { ADS_ONLY_SOURCES } from '../TrafficSourceFilter';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

const usdExact = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

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

function fmtAppointment(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const isToday = d.toDateString() === today.toDateString();
  const day = isToday
    ? 'Today'
    : d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  return `${day} · ${time}`;
}

type PresetKey = 'mtd' | '7d' | '30d' | 'custom';
type SegmentKey = 'all' | 'fresh' | 'returning';

export default function AnalyticsView({ initialData, slug }: { initialData: MarketingAnalyticsData; slug?: string }) {
  const [data, setData] = useState(initialData);
  const [preset, setPreset] = useState<PresetKey>('mtd');
  const [segment, setSegment] = useState<SegmentKey>('all');
  const [sources, setSources] = useState<Set<TrafficSourceKey>>(() => new Set(ADS_ONLY_SOURCES));
  const [from, setFrom] = useState(initialData.from);
  const [to, setTo] = useState(initialData.to);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function load(nextFrom: string, nextTo: string, nextSources: Set<TrafficSourceKey>) {
    setLoading(true);
    setError('');
    try {
      const result = await api.getMarketingAnalytics(nextFrom, nextTo, nextSources, slug);
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
    void load(range.from, range.to, sources);
  }

  function applyCustomRange() {
    setPreset('custom');
    void load(from, to, sources);
  }

  function changeSources(next: Set<TrafficSourceKey>) {
    setSources(next);
    void load(from, to, next);
  }

  const activeSegment: MarketingAnalyticsSegment = data[segment];

  const upcomingForSegment = useMemo(() => {
    const filtered = segment === 'all'
      ? data.upcoming
      : data.upcoming.filter((a) => (segment === 'fresh' ? a.freshFromAds : !a.freshFromAds));
    const total = filtered.reduce((sum, a) => sum + a.price, 0);
    return { appointments: filtered, total };
  }, [data.upcoming, segment]);

  return (
    <div>
      <TrafficSourceFilter
        selected={sources}
        onChange={changeSources}
        description="Counts customers whose first or latest touch matches the selected source(s)."
      />

      <div className="mt-4 flex flex-wrap gap-2">
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

      <SegmentTabs segment={segment} onChange={setSegment} />

      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Customers" value={activeSegment.customerCount.toLocaleString()} />
        <StatCard label="Services" value={activeSegment.serviceCount.toLocaleString()} />
        <StatCard label="Gross revenue" value={usd(activeSegment.grossRevenue)} />
      </div>

      <div className="mt-8">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-sm font-medium text-zinc-500">Anticipated from upcoming appointments</h2>
          <span className="text-xs text-zinc-400">not counted in the numbers above — nothing's been rung up yet</span>
        </div>

        {upcomingForSegment.appointments.length === 0 ? (
          <div className="mt-3 rounded-lg border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500">
            No upcoming appointments for {segmentLabel(segment).toLowerCase()} right now.
          </div>
        ) : (
          <>
            <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <StatCard label="Upcoming appointments" value={upcomingForSegment.appointments.length.toLocaleString()} />
              <StatCard label="Anticipated revenue" value={usd(upcomingForSegment.total)} />
            </div>
            <UpcomingList appointments={upcomingForSegment.appointments} />
          </>
        )}
      </div>

      <AdSpendRoi data={data} onSaved={(amount) => setData((d) => ({ ...d, adSpendThisMonth: amount }))} />
    </div>
  );
}

function segmentLabel(key: SegmentKey): string {
  return key === 'fresh' ? 'new customers' : key === 'returning' ? 'returning customers' : 'ads customers';
}

function SegmentTabs({ segment, onChange }: { segment: SegmentKey; onChange: (s: SegmentKey) => void }) {
  const tabs: { key: SegmentKey; label: string; hint: string }[] = [
    { key: 'all', label: 'All', hint: 'Every ads-attributed customer' },
    { key: 'fresh', label: 'New to Square', hint: 'The ad brought in a customer with no prior history' },
    { key: 'returning', label: 'Returning', hint: 'Already existed in Square, came back via an ad' },
  ];
  return (
    <div className="mt-6">
      <div className="inline-flex w-full flex-wrap gap-1 rounded-lg bg-zinc-100 p-1 sm:w-auto">
        {tabs.map((t) => (
          <button
            key={t.key}
            type="button"
            title={t.hint}
            onClick={() => onChange(t.key)}
            className={`flex-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors sm:flex-none ${
              segment === t.key
                ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200'
                : 'text-zinc-500 hover:text-zinc-700'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function FreshBadge({ fresh }: { fresh: boolean }) {
  return (
    <span
      className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${
        fresh ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-zinc-100 text-zinc-600 ring-zinc-200'
      }`}
    >
      {fresh ? 'New to Square' : 'Returning'}
    </span>
  );
}

function UpcomingList({ appointments }: { appointments: MarketingUpcomingAppointment[] }) {
  return (
    <>
      {/* Mobile cards */}
      <div className="mt-3 flex flex-col gap-2 sm:hidden">
        {appointments.map((a) => (
          <div key={a.customerId + a.startAt} className="rounded-lg p-3 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{a.customerName}</span>
              <FreshBadge fresh={a.freshFromAds} />
            </div>
            <div className="mt-1 flex items-center justify-between gap-2 text-sm text-zinc-600">
              <span>{a.serviceName}</span>
              <span className="font-medium tabular-nums">{usdExact(a.price)}</span>
            </div>
            <div className="mt-1 text-xs text-zinc-400">{fmtAppointment(a.startAt)}</div>
          </div>
        ))}
      </div>

      {/* Desktop table */}
      <div className="mt-3 hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Customer</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2">When</th>
              <th className="px-3 py-2 text-right">Price</th>
              <th className="px-3 py-2">Source</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {appointments.map((a) => (
              <tr key={a.customerId + a.startAt} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">{a.customerName}</td>
                <td className="px-3 py-2 text-zinc-600">{a.serviceName}</td>
                <td className="px-3 py-2 text-zinc-600">{fmtAppointment(a.startAt)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{usdExact(a.price)}</td>
                <td className="px-3 py-2"><FreshBadge fresh={a.freshFromAds} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

// Ad spend is always "this calendar month", independent of whatever [from, to] range is selected
// above — it pairs with currentMonthToDate, which the backend computes the same fixed way.
function AdSpendRoi({
  data, onSaved,
}: { data: MarketingAnalyticsData; onSaved: (amount: number) => void }) {
  const [spendInput, setSpendInput] = useState(data.adSpendThisMonth.toFixed(2));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const now = new Date();
  const monthLabel = now.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

  async function save() {
    const amount = Number(spendInput);
    if (!Number.isFinite(amount) || amount < 0) {
      setError('Enter a valid, non-negative amount.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await api.setAdSpend(now.getFullYear(), now.getMonth() + 1, amount);
      onSaved(result.amount);
      setSpendInput(result.amount.toFixed(2));
      setSavedAt(Date.now());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save ad spend.');
    } finally {
      setBusy(false);
    }
  }

  const revenue = data.currentMonthToDate.grossRevenue;
  const spend = data.adSpendThisMonth;
  const hasSpend = spend > 0;
  const roiMultiple = hasSpend ? revenue / spend : null;
  const roiPercent = hasSpend ? ((revenue - spend) / spend) * 100 : null;

  return (
    <div className="mt-8 border-t border-zinc-100 pt-8">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-medium text-zinc-500">Ads ROI — {monthLabel}</h2>
        <span className="text-xs text-zinc-400">always this calendar month, regardless of the range above</span>
      </div>

      <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Ad spend so far this month</span>
          <div className="flex items-center gap-1">
            <span className="text-zinc-400">$</span>
            <input
              type="number"
              min="0"
              step="0.01"
              inputMode="decimal"
              value={spendInput}
              onChange={(e) => setSpendInput(e.target.value)}
              className="w-32 rounded border border-zinc-300 px-2 py-1.5 text-sm"
            />
          </div>
        </label>
        <button
          type="button"
          disabled={busy}
          onClick={save}
          className="rounded bg-zinc-800 px-3 py-1.5 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          {busy ? 'Saving…' : 'Save'}
        </button>
        {savedAt !== null && !busy && <span className="text-xs text-emerald-600">Saved</span>}
      </div>
      {error ? <p className="mt-2 text-sm text-red-600">{error}</p> : null}

      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Revenue this month" value={usd(revenue)} />
        <StatCard label="Ad spend this month" value={usd(spend)} />
        {hasSpend ? (
          <StatCard
            label="Return on ad spend"
            value={`${roiMultiple!.toFixed(1)}x`}
            hint={`${roiPercent! >= 0 ? '+' : ''}${roiPercent!.toFixed(0)}% ROI`}
          />
        ) : (
          <div className="rounded-lg p-4 text-xs text-zinc-400 ring-1 ring-dashed ring-zinc-200">
            Enter this month&apos;s ad spend above to see ROI.
          </div>
        )}
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

function StatCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</div>
      <div className="mt-1 text-2xl font-semibold text-zinc-900">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-zinc-500">{hint}</div>}
    </div>
  );
}
