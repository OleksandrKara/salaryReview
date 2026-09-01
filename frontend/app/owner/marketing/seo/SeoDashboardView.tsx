'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { SeoCoreWebVitals, SeoOverviewDto } from '../../../lib/types';

const CHART_COLORS = { clicks: '#2563eb', impressions: '#a1a1aa' };

// Google's own published Core Web Vitals thresholds (backend CoreWebVitalsThresholds, cited there
// with source/date) — duplicated here only as display cutoffs for the color badge, not re-derived.
const LCP_GOOD_MS = 2500;
const LCP_POOR_MS = 4000;
const CLS_GOOD = 0.1;
const CLS_POOR = 0.25;

// "Last synced Jul 22 at 2:30 PM" — same absolute-time convention as MarketingTabs' own
// fmtLastSyncedAt, re-declared locally since it's a small pure function, not exported there.
function fmtAbsolute(iso: string | null): string {
  if (!iso) return 'never';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return 'never';
  const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  return `${date} at ${time}`;
}

function cwvBadgeColor(metric: 'lcp' | 'cls', value: number | null): string {
  if (value == null) return 'bg-zinc-100 text-zinc-500';
  const [good, poor] = metric === 'lcp' ? [LCP_GOOD_MS, LCP_POOR_MS] : [CLS_GOOD, CLS_POOR];
  if (value <= good) return 'bg-emerald-100 text-emerald-700';
  if (value <= poor) return 'bg-amber-100 text-amber-700';
  return 'bg-rose-100 text-rose-700';
}

const SEVERITY_BADGE: Record<string, string> = {
  POOR: 'bg-rose-100 text-rose-700',
  NEEDS_IMPROVEMENT: 'bg-amber-100 text-amber-700',
  ADVISORY: 'bg-zinc-100 text-zinc-600',
};

function CoreWebVitalsCard({ label, vitals }: { label: string; vitals: SeoCoreWebVitals | null }) {
  return (
    <div className="flex-1 rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-zinc-700">{label}</span>
        {vitals && <span className="text-xs text-zinc-400">{new Date(vitals.date).toLocaleDateString()}</span>}
      </div>
      {!vitals ? (
        <p className="mt-3 text-sm text-zinc-400">Waiting for the first weekly PageSpeed check.</p>
      ) : (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${cwvBadgeColor('lcp', vitals.lcpMs)}`}>
            LCP {vitals.lcpMs != null ? `${(vitals.lcpMs / 1000).toFixed(1)}s` : '—'}
          </span>
          <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${cwvBadgeColor('cls', vitals.cls)}`}>
            CLS {vitals.cls != null ? vitals.cls.toFixed(3) : '—'}
          </span>
          <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-semibold text-zinc-600">
            Score {vitals.performanceScore ?? '—'}
          </span>
          {/* FCP/TBT are secondary diagnostics, not one of the three pass/fail gates — hidden below
              sm same as RevenueChart's MoM row, per design.md D7. */}
          <span className="hidden text-xs text-zinc-400 sm:inline">
            FCP {vitals.fcpMs != null ? `${(vitals.fcpMs / 1000).toFixed(1)}s` : '—'} · TBT {vitals.tbtMs ?? '—'}ms
          </span>
        </div>
      )}
    </div>
  );
}

export default function SeoDashboardView({ initialData }: { initialData: SeoOverviewDto }) {
  const [data, setData] = useState(initialData);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  async function syncNow() {
    setSyncing(true);
    setSyncError(null);
    try {
      setData(await api.syncSeoNow());
    } catch (e) {
      setSyncError(e instanceof Error ? e.message : 'Sync failed. Please try again.');
    } finally {
      setSyncing(false);
    }
  }

  if (!data.connected) {
    return (
      <div className="rounded-lg p-8 text-center ring-1 ring-zinc-200">
        <p className="text-sm text-zinc-600">SEO monitoring is on for this business, but no credentials are connected yet.</p>
        <Link
          href="/owner/settings/seo"
          className="mt-4 inline-flex items-center gap-2 rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white"
        >
          Connect Search Console, GA4 &amp; PageSpeed
        </Link>
      </div>
    );
  }

  const chartData = data.trend.map((p) => ({
    date: new Date(p.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    clicks: p.clicks,
    impressions: p.impressions,
  }));
  const latestCtr = data.trend.length ? data.trend[data.trend.length - 1].ctr : null;
  const latestPosition = data.trend.length ? data.trend[data.trend.length - 1].position : null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-zinc-400">
          Last synced {fmtAbsolute(data.lastSyncAt)}
          {data.lastSyncError && <span className="ml-2 text-amber-600">— last attempt failed: {data.lastSyncError}</span>}
        </p>
        <button
          type="button"
          onClick={syncNow}
          disabled={syncing}
          className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {syncing && <Spinner className="h-4 w-4 text-white" />}
          {syncing ? 'Syncing…' : 'Sync now'}
        </button>
      </div>
      {syncError && <p className="text-sm text-red-600">{syncError}</p>}

      {data.activeIssues.length > 0 && (
        <div className="rounded-lg ring-1 ring-zinc-200">
          <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">
            Active recommendations ({data.activeIssues.length})
          </p>
          <ul className="divide-y divide-zinc-100">
            {data.activeIssues.map((issue, i) => (
              <li key={i} className="flex flex-wrap items-start gap-2 px-4 py-3">
                <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold ${SEVERITY_BADGE[issue.severity] ?? 'bg-zinc-100 text-zinc-600'}`}>
                  {issue.issueType}
                </span>
                <span className="text-sm text-zinc-700">{issue.detail}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="flex flex-col gap-4 sm:flex-row">
        <CoreWebVitalsCard label="Mobile" vitals={data.mobile} />
        <CoreWebVitalsCard label="Desktop" vitals={data.desktop} />
      </div>

      <div className="rounded-lg p-3 ring-1 ring-zinc-200 sm:p-4">
        <div className="mb-3 flex flex-wrap items-center gap-4 text-sm">
          <span className="font-medium text-zinc-700">Search trend (last 28 days)</span>
          <span className="text-zinc-500">CTR: {latestCtr != null ? `${(latestCtr * 100).toFixed(1)}%` : '—'}</span>
          <span className="text-zinc-500">Avg. position: {latestPosition ?? '—'}</span>
        </div>
        {chartData.length === 0 ? (
          <p className="py-8 text-center text-sm text-zinc-400">No Search Console data synced yet.</p>
        ) : (
          <div style={{ width: '100%', height: 280 }}>
            <ResponsiveContainer>
              <LineChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e4e4e7" vertical={false} />
                <XAxis dataKey="date" tick={{ fontSize: 12, fill: '#71717a' }} axisLine={{ stroke: '#e4e4e7' }} tickLine={false} />
                <YAxis yAxisId="clicks" tick={{ fontSize: 12, fill: '#71717a' }} axisLine={false} tickLine={false} width={40} />
                <YAxis yAxisId="impressions" orientation="right" tick={{ fontSize: 12, fill: '#71717a' }} axisLine={false} tickLine={false} width={48} />
                <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8 }} />
                <Line yAxisId="clicks" type="monotone" dataKey="clicks" name="Clicks" stroke={CHART_COLORS.clicks} strokeWidth={2} dot={false} />
                <Line yAxisId="impressions" type="monotone" dataKey="impressions" name="Impressions" stroke={CHART_COLORS.impressions} strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      <div className="rounded-lg ring-1 ring-zinc-200">
        <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">Top queries</p>
        {data.topQueries.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-zinc-400">No queries synced yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-zinc-400">
                  <th className="px-4 py-2 font-medium">Query</th>
                  <th className="px-4 py-2 font-medium">Clicks</th>
                  <th className="px-4 py-2 font-medium">Impressions</th>
                  <th className="px-4 py-2 font-medium">CTR</th>
                  <th className="px-4 py-2 font-medium">Avg. position</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {data.topQueries.map((row) => (
                  <tr key={row.query}>
                    <td className="max-w-xs truncate px-4 py-2 text-zinc-700">{row.query}</td>
                    <td className="px-4 py-2 text-zinc-600">{row.clicks.toLocaleString()}</td>
                    <td className="px-4 py-2 text-zinc-600">{row.impressions.toLocaleString()}</td>
                    <td className="px-4 py-2 text-zinc-600">{(row.ctr * 100).toFixed(1)}%</td>
                    <td className="px-4 py-2 text-zinc-600">{row.position}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
