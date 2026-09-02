'use client';

import Link from 'next/link';
import { useState, type FormEvent } from 'react';
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
import type {
  SeoCannibalizedQuery,
  SeoCoreWebVitals,
  SeoOpportunity,
  SeoOverviewDto,
  SeoPageChange,
  SeoPageOpportunity,
  SeoPeriodComparison,
  SeoQueryChange,
  SeoTrackedQueryRow,
} from '../../../lib/types';

const CHART_COLORS = { clicks: '#2563eb', impressions: '#a1a1aa' };
const ANALYTICS_CHART_COLORS = { totalUsers: '#7c3aed', organicSessions: '#0d9488' };

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

// For plain counts (users, sessions) — unlike positionDeltaLabel, a positive delta here just means
// "more," which is always the emerald/good direction (no position-style sign inversion needed).
function countDeltaLabel(delta: number | null): { text: string; className: string } {
  if (delta == null) return { text: '', className: 'text-zinc-400' };
  if (delta === 0) return { text: '±0', className: 'text-zinc-500' };
  const sign = delta > 0 ? '+' : '';
  return { text: `${sign}${delta.toLocaleString()}`, className: delta > 0 ? 'text-emerald-600' : 'text-rose-500' };
}

function positionDeltaLabel(delta: number | null): { text: string; className: string } {
  if (delta == null) return { text: '—', className: 'text-zinc-400' };
  // Round to the same 1-decimal precision as the displayed text *before* the zero check — a
  // delta like -0.03 is functionally flat but fails `=== 0`, and would otherwise render as a
  // confusing rose "-0.0" (looks like a decline for what's actually a no-op rounding artifact).
  const rounded = Math.round(delta * 10) / 10;
  if (rounded === 0) return { text: '0.0', className: 'text-zinc-500' };
  // Same "positive is good" sign convention/coloring as RevenueChart's own MoM row — positive here
  // means the query moved to a numerically lower (better) search position.
  const sign = rounded > 0 ? '+' : '';
  return { text: `${sign}${rounded.toFixed(1)}`, className: rounded > 0 ? 'text-emerald-600' : 'text-rose-500' };
}

// Percentage-point CTR delta — positive is always good (higher CTR), no position-style sign
// inversion needed, but formatted as "pp" (not a raw count) since CTR itself is a fraction.
function ctrDeltaLabel(deltaFraction: number | null): { text: string; className: string } {
  if (deltaFraction == null) return { text: '—', className: 'text-zinc-400' };
  const pp = deltaFraction * 100;
  if (Math.abs(pp) < 0.05) return { text: '±0.0pp', className: 'text-zinc-500' };
  const sign = pp > 0 ? '+' : '';
  return { text: `${sign}${pp.toFixed(1)}pp`, className: pp > 0 ? 'text-emerald-600' : 'text-rose-500' };
}

/** One period's clicks/impressions/CTR/avg-position change vs. the equivalent immediately-prior
 * period — renders nothing at all when there's no prior-period data yet (business too new for
 * this specific comparison), rather than a misleading zero-filled row. */
function PeriodStat({ label, comparison }: { label: string; comparison: SeoPeriodComparison | null }) {
  if (!comparison?.previous) return null;
  const clicks = countDeltaLabel(comparison.current.clicks - comparison.previous.clicks);
  const impressions = countDeltaLabel(comparison.current.impressions - comparison.previous.impressions);
  const ctr = ctrDeltaLabel(comparison.current.ctr - comparison.previous.ctr);
  // Position: previous - current (not current - previous) — a numerically lower position is
  // better, same inversion positionDeltaLabel's own callers already apply everywhere else.
  const position = positionDeltaLabel(comparison.previous.position - comparison.current.position);
  return (
    <div className="flex-1 rounded-lg p-3 ring-1 ring-zinc-200">
      <p className="text-xs font-medium text-zinc-500">{label}</p>
      <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1.5 text-sm sm:grid-cols-4">
        <div>
          <dt className="text-xs text-zinc-400">Clicks</dt>
          <dd className={`font-medium ${clicks.className}`}>{clicks.text}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-400">Impressions</dt>
          <dd className={`font-medium ${impressions.className}`}>{impressions.text}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-400">CTR</dt>
          <dd className={`font-medium ${ctr.className}`}>{ctr.text}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-400">Avg. position</dt>
          <dd className={`font-medium ${position.className}`}>{position.text}</dd>
        </div>
      </dl>
    </div>
  );
}

const OPPORTUNITY_LABEL: Record<string, string> = {
  STRIKING_DISTANCE: 'Striking distance',
  HIGH_IMPRESSIONS_LOW_CTR: 'High impressions, low CTR',
  GROWING_IMPRESSIONS: 'Growing impressions',
};

/** Shared by both the "Biggest wins" and "Biggest losses" lists — same shape, different sign of
 * data and different empty-state copy. Only significant moves ever reach this list at all (the
 * backend's SeoChangeDetectionService already filters out day-to-day noise), so every row here is
 * meant to be worth the owner's attention. */
function QueryChangeList({
  title,
  changes,
  emptyText,
}: {
  title: string;
  changes: SeoQueryChange[];
  emptyText: string;
}) {
  return (
    <div className="flex-1 rounded-lg ring-1 ring-zinc-200">
      <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">{title}</p>
      {changes.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-zinc-400">{emptyText}</p>
      ) : (
        <ul className="divide-y divide-zinc-100">
          {changes.map((c) => {
            const delta = positionDeltaLabel(c.positionDelta);
            return (
              <li key={c.query} className="flex items-center justify-between gap-2 px-4 py-2 text-sm">
                <span className="max-w-[9rem] truncate text-zinc-700 sm:max-w-xs">{c.query}</span>
                <span className="shrink-0 text-zinc-500">
                  {c.previousPosition.toFixed(1)} → {c.currentPosition.toFixed(1)}{' '}
                  <span className={`font-medium ${delta.className}`}>{delta.text}</span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** Striking-distance/high-impression-low-CTR/growing-impression queries — flagged as a "potential
 * opportunity" only, never asserted as a confirmed problem (see backend
 * SeoChangeDetectionService's own doc comment). */
function OpportunitiesCard({ opportunities }: { opportunities: SeoOpportunity[] }) {
  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">Opportunities</p>
      {opportunities.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-zinc-400">No opportunities detected in this window.</p>
      ) : (
        <ul className="divide-y divide-zinc-100">
          {opportunities.map((o) => (
            <li key={o.query} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2 text-sm">
              <span className="max-w-[9rem] truncate text-zinc-700 sm:max-w-xs">{o.query}</span>
              <span className="flex flex-wrap items-center gap-2 text-xs text-zinc-500">
                <span className="rounded-full bg-zinc-100 px-2 py-0.5 font-medium text-zinc-600">
                  {OPPORTUNITY_LABEL[o.reason] ?? o.reason}
                </span>
                <span>
                  pos {o.currentPosition.toFixed(1)} · {o.currentImpressions.toLocaleString()} impr
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// Percentage change for page-level impressions (not a position, so no sign-inversion concern) —
// whole-percent precision since page-level swings are usually large enough that a decimal adds
// noise rather than clarity.
function pageChangePercentLabel(ratio: number): { text: string; className: string } {
  const pct = ratio * 100;
  const sign = pct > 0 ? '+' : '';
  return { text: `${sign}${pct.toFixed(0)}%`, className: pct > 0 ? 'text-emerald-600' : 'text-rose-500' };
}

/** Shared by "Winning pages" and "Losing pages" — same shape as QueryChangeList, keyed by page
 * and showing an impressions before/after instead of a position before/after (pages don't have a
 * single coherent "position" the way one query does). */
function PageChangeList({
  title,
  changes,
  emptyText,
}: {
  title: string;
  changes: SeoPageChange[];
  emptyText: string;
}) {
  return (
    <div className="flex-1 rounded-lg ring-1 ring-zinc-200">
      <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">{title}</p>
      {changes.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-zinc-400">{emptyText}</p>
      ) : (
        <ul className="divide-y divide-zinc-100">
          {changes.map((c) => {
            const delta = pageChangePercentLabel(c.changeRatio);
            return (
              <li key={c.page} className="flex items-center justify-between gap-2 px-4 py-2 text-sm">
                <span className="max-w-[9rem] truncate text-zinc-700 sm:max-w-xs">{c.page}</span>
                <span className="shrink-0 text-zinc-500">
                  {c.previousImpressions.toLocaleString()} → {c.currentImpressions.toLocaleString()}{' '}
                  <span className={`font-medium ${delta.className}`}>{delta.text}</span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** Shared by "Underperforming pages" (real demand, weak position) and "Content opportunities"
 * (position 5-20 — a more achievable rewrite/expand target). */
function PageOpportunityList({
  title,
  opportunities,
  emptyText,
}: {
  title: string;
  opportunities: SeoPageOpportunity[];
  emptyText: string;
}) {
  return (
    <div className="flex-1 rounded-lg ring-1 ring-zinc-200">
      <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">{title}</p>
      {opportunities.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-zinc-400">{emptyText}</p>
      ) : (
        <ul className="divide-y divide-zinc-100">
          {opportunities.map((o) => (
            <li key={o.page} className="flex items-center justify-between gap-2 px-4 py-2 text-sm">
              <span className="max-w-[9rem] truncate text-zinc-700 sm:max-w-xs">{o.page}</span>
              <span className="shrink-0 text-xs text-zinc-500">
                pos {o.currentPosition.toFixed(1)} · {o.currentImpressions.toLocaleString()} impr
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Flags a query where more than one page holds a meaningful share of its impressions — labeled a
 * "potential optimization opportunity," never asserted as a confirmed problem (a business can
 * legitimately have two pages both reasonably ranking for a broad query). Pages are pre-sorted by
 * impressions descending (backend), so the first entry reads as the presumed "intended" page. */
function CannibalizationCard({ queries }: { queries: SeoCannibalizedQuery[] }) {
  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <p className="border-b border-zinc-100 px-4 py-2 text-sm font-medium text-zinc-700">
        Possible keyword overlap
      </p>
      {queries.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-zinc-400">
          No query is being competed for by more than one page.
        </p>
      ) : (
        <ul className="divide-y divide-zinc-100">
          {queries.map((cq) => (
            <li key={cq.query} className="px-4 py-3 text-sm">
              <p className="font-medium text-zinc-700">{cq.query}</p>
              <p className="mt-0.5 text-xs text-zinc-400">
                Potential optimization opportunity — more than one page ranks for this query.
              </p>
              <ul className="mt-2 flex flex-col gap-1">
                {cq.pages.map((p) => (
                  <li key={p.page} className="flex items-center justify-between gap-2 text-xs text-zinc-600">
                    <span className="max-w-[10rem] truncate sm:max-w-sm">{p.page}</span>
                    <span className="shrink-0">
                      {(p.share * 100).toFixed(0)}% · pos {p.position.toFixed(1)}
                    </span>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** "Main queries" section — the hybrid approach: shows the owner's own pinned list once any exist,
 * otherwise falls back to the top-impression queries in this window (flagged "Suggested") so the
 * section is never empty on a business that hasn't curated one yet. Position deltas compare the
 * earlier vs. later half of the selected window (see SeoDashboardService.TrackedQueryRow's own doc
 * comment for the exact split). */
function TrackedQueriesCard({
  rows,
  onPin,
  onUnpin,
  pending,
}: {
  rows: SeoTrackedQueryRow[];
  onPin: (query: string) => void;
  onUnpin: (query: string) => void;
  pending: string | null;
}) {
  const [newQuery, setNewQuery] = useState('');
  const autoSuggested = rows.length > 0 && rows[0].autoSuggested;

  function submitNewQuery(e: FormEvent) {
    e.preventDefault();
    const trimmed = newQuery.trim();
    if (!trimmed) return;
    onPin(trimmed);
    setNewQuery('');
  }

  return (
    <div className="rounded-lg ring-1 ring-zinc-200">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-100 px-4 py-2">
        <p className="text-sm font-medium text-zinc-700">Main queries</p>
        {autoSuggested && (
          <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-500">
            Suggested by impressions — pin your own below
          </span>
        )}
      </div>

      {rows.length === 0 ? (
        <p className="px-4 py-8 text-center text-sm text-zinc-400">No query data synced yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-zinc-400">
                <th className="px-4 py-2 font-medium">Query</th>
                <th className="px-4 py-2 font-medium">Position</th>
                <th className="px-4 py-2 font-medium">Change</th>
                <th className="hidden px-4 py-2 font-medium sm:table-cell">Impressions</th>
                <th className="px-4 py-2 font-medium" />
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {rows.map((row) => {
                const delta = positionDeltaLabel(row.positionDelta);
                return (
                  <tr key={row.query}>
                    <td className="max-w-[10rem] truncate px-4 py-2 text-zinc-700 sm:max-w-xs">{row.query}</td>
                    <td className="px-4 py-2 text-zinc-600">{row.currentPosition ?? '—'}</td>
                    <td className={`px-4 py-2 font-medium ${delta.className}`}>{delta.text}</td>
                    <td className="hidden px-4 py-2 text-zinc-600 sm:table-cell">
                      {row.currentImpressions.toLocaleString()}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {row.autoSuggested ? (
                        <button
                          type="button"
                          onClick={() => onPin(row.query)}
                          disabled={pending === row.query}
                          className="rounded-full px-2 py-1 text-xs font-medium text-zinc-500 ring-1 ring-zinc-200 hover:bg-zinc-50 disabled:opacity-50"
                        >
                          {pending === row.query ? '…' : 'Pin'}
                        </button>
                      ) : (
                        <button
                          type="button"
                          onClick={() => onUnpin(row.query)}
                          disabled={pending === row.query}
                          aria-label={`Stop tracking "${row.query}"`}
                          className="rounded-full px-2 py-1 text-xs font-medium text-zinc-400 hover:bg-rose-50 hover:text-rose-600 disabled:opacity-50"
                        >
                          {pending === row.query ? '…' : '×'}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <form onSubmit={submitNewQuery} className="flex flex-col gap-2 border-t border-zinc-100 p-3 sm:flex-row">
        <input
          type="text"
          value={newQuery}
          onChange={(e) => setNewQuery(e.target.value)}
          placeholder="Pin a query you want to rank for…"
          className="min-w-0 flex-1 rounded-lg border border-zinc-200 px-3 py-1.5 text-sm placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none"
        />
        <button
          type="submit"
          disabled={!newQuery.trim() || pending !== null}
          className="rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          Pin
        </button>
      </form>
    </div>
  );
}

export default function SeoDashboardView({ initialData }: { initialData: SeoOverviewDto }) {
  const [data, setData] = useState(initialData);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [trackedQueryPending, setTrackedQueryPending] = useState<string | null>(null);
  const [trackedQueryError, setTrackedQueryError] = useState<string | null>(null);

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

  async function pinQuery(query: string) {
    setTrackedQueryPending(query);
    setTrackedQueryError(null);
    try {
      setData(await api.addSeoTrackedQuery(query));
    } catch (e) {
      setTrackedQueryError(e instanceof Error ? e.message : 'Could not pin that query. Please try again.');
    } finally {
      setTrackedQueryPending(null);
    }
  }

  async function unpinQuery(query: string) {
    setTrackedQueryPending(query);
    setTrackedQueryError(null);
    try {
      setData(await api.removeSeoTrackedQuery(query));
    } catch (e) {
      setTrackedQueryError(e instanceof Error ? e.message : 'Could not unpin that query. Please try again.');
    } finally {
      setTrackedQueryPending(null);
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

  const analyticsChartData = data.analyticsTrend.map((p) => ({
    date: new Date(p.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    totalUsers: p.totalUsers,
    organicSessions: p.organicSessions,
  }));
  // Earlier-half vs. later-half average, same "before/after within the window" split as the tracked
  // queries' position delta below — a single day's total is noisy, but the two-half average isn't.
  function halfOverHalfDelta(values: number[]): number | null {
    if (values.length < 2) return null;
    const mid = Math.floor(values.length / 2);
    const avg = (xs: number[]) => xs.reduce((sum, v) => sum + v, 0) / xs.length;
    return Math.round(avg(values.slice(mid)) - avg(values.slice(0, mid)));
  }
  const usersDelta = halfOverHalfDelta(data.analyticsTrend.map((p) => p.totalUsers));
  const organicDelta = halfOverHalfDelta(data.analyticsTrend.map((p) => p.organicSessions));
  const usersDeltaLabel = countDeltaLabel(usersDelta);
  const organicDeltaLabel = countDeltaLabel(organicDelta);

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

      {(data.last7Days?.previous || data.last28Days?.previous || data.yearOverYear?.previous) && (
        <div className="flex flex-col gap-3 sm:flex-row">
          <PeriodStat label="vs. last 7 days" comparison={data.last7Days} />
          <PeriodStat label="vs. last 28 days" comparison={data.last28Days} />
          <PeriodStat label="vs. last year" comparison={data.yearOverYear} />
        </div>
      )}

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

      <div className="flex flex-col gap-4 sm:flex-row">
        <QueryChangeList
          title={`Biggest wins (${data.gainers.length})`}
          changes={data.gainers}
          emptyText="No significant gains detected in this window."
        />
        <QueryChangeList
          title={`Biggest losses (${data.losers.length})`}
          changes={data.losers}
          emptyText="No significant losses detected in this window."
        />
      </div>

      <OpportunitiesCard opportunities={data.opportunities} />

      <div className="rounded-lg p-3 ring-1 ring-zinc-200 sm:p-4">
        <div className="mb-3 flex flex-wrap items-center gap-4 text-sm">
          <span className="font-medium text-zinc-700">Organic traffic &amp; unique users (last 28 days)</span>
          {usersDelta != null && (
            <span className="text-zinc-500">
              Users: <span className={usersDeltaLabel.className}>{usersDeltaLabel.text}</span>{' '}
              <span className="text-zinc-400">vs. earlier in the window</span>
            </span>
          )}
          {organicDelta != null && (
            <span className="hidden text-zinc-500 sm:inline">
              Organic sessions: <span className={organicDeltaLabel.className}>{organicDeltaLabel.text}</span>
            </span>
          )}
        </div>
        {analyticsChartData.length === 0 ? (
          <p className="py-8 text-center text-sm text-zinc-400">No GA4 data synced yet.</p>
        ) : (
          <div style={{ width: '100%', height: 280 }}>
            <ResponsiveContainer>
              <LineChart data={analyticsChartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e4e4e7" vertical={false} />
                <XAxis dataKey="date" tick={{ fontSize: 12, fill: '#71717a' }} axisLine={{ stroke: '#e4e4e7' }} tickLine={false} />
                <YAxis tick={{ fontSize: 12, fill: '#71717a' }} axisLine={false} tickLine={false} width={40} />
                <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8 }} />
                <Line type="monotone" dataKey="totalUsers" name="Unique users" stroke={ANALYTICS_CHART_COLORS.totalUsers} strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="organicSessions" name="Organic sessions" stroke={ANALYTICS_CHART_COLORS.organicSessions} strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      {trackedQueryError && <p className="text-sm text-red-600">{trackedQueryError}</p>}
      <TrackedQueriesCard
        rows={data.trackedQueries}
        onPin={pinQuery}
        onUnpin={unpinQuery}
        pending={trackedQueryPending}
      />

      <div className="flex flex-col gap-4 sm:flex-row">
        <PageChangeList
          title={`Winning pages (${data.winningPages.length})`}
          changes={data.winningPages}
          emptyText="No pages gained significant impressions in this window."
        />
        <PageChangeList
          title={`Losing pages (${data.losingPages.length})`}
          changes={data.losingPages}
          emptyText="No pages lost significant impressions in this window."
        />
      </div>

      <div className="flex flex-col gap-4 sm:flex-row">
        <PageOpportunityList
          title="Underperforming pages"
          opportunities={data.underperformingPages}
          emptyText="No pages with real demand and a weak ranking detected."
        />
        <PageOpportunityList
          title="Content opportunities (rank 5–20)"
          opportunities={data.contentOpportunities}
          emptyText="No pages in the content-opportunity band right now."
        />
      </div>

      <CannibalizationCard queries={data.cannibalizedQueries} />

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
