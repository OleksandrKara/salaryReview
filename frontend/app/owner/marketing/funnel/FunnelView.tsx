'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../lib/api';
import type { FunnelAnalysisResult, FunnelDashboardData, MarketingLandingPage, Role } from '../../../lib/types';
import TrafficModeToggle, { type TrafficMode } from '../TrafficModeToggle';

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

const IMPACT_STYLES: Record<string, string> = {
  HIGH: 'bg-red-50 text-red-700 ring-red-200',
  MEDIUM: 'bg-amber-50 text-amber-700 ring-amber-200',
  LOW: 'bg-zinc-100 text-zinc-600 ring-zinc-200',
};

/** "3 hours ago" style — falls back to a plain date past a week so old history entries don't
 * show an awkward "12 days ago". Full timestamp is always still available via the title attr. */
function relativeTime(iso: string): string {
  const diffSec = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (diffSec < 60) return 'just now';
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin} minute${diffMin === 1 ? '' : 's'} ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr} hour${diffHr === 1 ? '' : 's'} ago`;
  const diffDay = Math.floor(diffHr / 24);
  if (diffDay < 7) return `${diffDay} day${diffDay === 1 ? '' : 's'} ago`;
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function AnalysisResultView({ result }: { result: FunnelAnalysisResult }) {
  return (
    <div className="mt-4 space-y-4 rounded-lg border border-indigo-100 bg-indigo-50/40 p-4">
      <div>
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs font-semibold uppercase tracking-wide text-indigo-700">Biggest bottleneck: {result.biggestBottleneckStep}</p>
          <span className="shrink-0 text-xs text-zinc-400" title={new Date(result.createdAt).toLocaleString('en-US')}>
            Analyzed {relativeTime(result.createdAt)}
          </span>
        </div>
        <p className="mt-1 text-sm text-zinc-700">{result.bottleneckExplanation}</p>
      </div>

      {result.recommendations.length > 0 && (
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Recommendations</p>
          <ul className="mt-2 space-y-2">
            {result.recommendations.map((r) => (
              <li key={r.title} className="rounded border border-zinc-200 bg-white p-2.5">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-medium text-zinc-900">{r.title}</span>
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${IMPACT_STYLES[r.expectedImpact] ?? IMPACT_STYLES.LOW}`}>
                    {r.expectedImpact}
                  </span>
                </div>
                <p className="mt-1 text-xs text-zinc-600">{r.rationale}</p>
              </li>
            ))}
          </ul>
        </div>
      )}

      {result.suspiciousPatterns.length > 0 && (
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Worth a second look</p>
          <ul className="mt-1 list-disc space-y-1 pl-4 text-sm text-zinc-700">
            {result.suspiciousPatterns.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}

      {result.suggestedAbTests.length > 0 && (
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Suggested A/B tests</p>
          <ul className="mt-1 list-disc space-y-1 pl-4 text-sm text-zinc-700">
            {result.suggestedAbTests.map((t) => (
              <li key={t}>{t}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="border-t border-indigo-100 pt-3">
        <p className="text-xs font-semibold uppercase tracking-wide text-indigo-700">Top priority</p>
        <p className="mt-1 text-sm font-medium text-zinc-900">{result.topPriorityAction}</p>
      </div>
    </div>
  );
}

/** Bar width is reachedCount/totalStarted (not totalVisitors) — this is what makes two funnels
 * with a different number/names of steps visually comparable: both scale 0-100% against their
 * own "started booking" baseline, so the *shape* of the drop-off is comparable at a glance even
 * though the step labels underneath differ (e.g. mani asks for contact first; homepage last).
 */
function FunnelPanel({
  label,
  slug,
  data,
  canAnalyze,
  trafficMode,
}: {
  label: string;
  slug: string;
  data: FunnelDashboardData;
  canAnalyze: boolean;
  trafficMode: TrafficMode;
}) {
  // history[0] (if present) is "the" current analysis shown; history[1:] is the collapsed past-
  // analyses list. null means "not fetched yet", [] means "fetched, nothing analyzed before".
  const [history, setHistory] = useState<FunnelAnalysisResult[] | null>(null);
  const [loadingHistory, setLoadingHistory] = useState(canAnalyze);
  // Which action is in flight, so the two buttons can show distinct labels/disable together
  // without a second boolean to keep in sync.
  const [runningAction, setRunningAction] = useState<'normal' | 'force' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedHistory, setExpandedHistory] = useState<Set<number>>(new Set());

  useEffect(() => {
    // canAnalyze/slug/data.flowKey are effectively constant for this component's lifetime — the
    // parent remounts the whole panel (via its `key`) whenever trafficMode changes, so this only
    // ever needs to run once per mount. loadingHistory already starts as `canAnalyze` (see
    // useState above), so there's no separate setState-to-true call needed here.
    if (!canAnalyze) return;
    let cancelled = false;
    api
      .getFunnelAnalysisHistory(slug, data.flowKey)
      .then((h) => {
        if (!cancelled) setHistory(h);
      })
      .catch(() => {
        // Soft-fail: history is a nice-to-have on load, not worth an error banner before the
        // owner has even clicked anything. The Analyze button still works if this fails.
        if (!cancelled) setHistory([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingHistory(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function runAnalysis(force: boolean) {
    setRunningAction(force ? 'force' : 'normal');
    setError(null);
    try {
      await api.analyzeFunnel(slug, data.flowKey, trafficMode, force);
      // Re-fetch rather than splice the new result in locally — a non-forced call may have just
      // returned the existing cached entry, and re-fetching sidesteps having to guess which.
      const fresh = await api.getFunnelAnalysisHistory(slug, data.flowKey);
      setHistory(fresh);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Analysis failed.');
    } finally {
      setRunningAction(null);
    }
  }

  function toggleHistoryRow(idx: number) {
    setExpandedHistory((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  }

  const latest = history?.[0] ?? null;
  const older = history?.slice(1) ?? [];

  return (
    <div className="rounded-lg border border-zinc-200 p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="font-medium text-zinc-900">{label}</h3>
        <span className="text-xs text-zinc-400">{data.flowKey}</span>
      </div>

      <dl className="mt-3 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        <div>
          <dt className="text-xs text-zinc-500">Visitors</dt>
          <dd className="font-semibold tabular-nums">{data.totalVisitors.toLocaleString('en-US')}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Started booking</dt>
          <dd className="font-semibold tabular-nums">{data.totalStarted.toLocaleString('en-US')}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Completed</dt>
          <dd className="font-semibold tabular-nums">{data.totalCompleted.toLocaleString('en-US')}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Conversion (of visitors)</dt>
          <dd className="font-semibold tabular-nums">{pct(data.finalConversionRate)}</dd>
        </div>
      </dl>

      <div className="mt-4 space-y-3">
        {data.steps.map((step) => (
          <div key={step.stepKey}>
            <div className="flex items-center justify-between text-xs">
              <span className="font-medium text-zinc-700">
                Step {step.stepIndex + 1} of {step.stepCountTotal}: {step.stepKey}
              </span>
              <span className="text-zinc-500 tabular-nums">
                {step.reachedCount.toLocaleString('en-US')} ({pct(step.reachedPctOfStarted)})
              </span>
            </div>
            <div className="mt-1 h-3 w-full overflow-hidden rounded-full bg-zinc-100">
              <div
                className="h-full rounded-full bg-blue-500"
                style={{ width: `${Math.min(100, step.reachedPctOfStarted * 100)}%` }}
              />
            </div>
            {step.dropOffCount > 0 && (
              <p className="mt-0.5 text-xs text-red-600">
                −{step.dropOffCount.toLocaleString('en-US')} dropped off here ({pct(step.dropOffPct)})
              </p>
            )}
          </div>
        ))}
      </div>

      {canAnalyze && (
        <div className="mt-4 border-t border-zinc-100 pt-3">
          {loadingHistory ? (
            <p className="text-xs text-zinc-400">Checking for a previous analysis…</p>
          ) : (
            <div className="flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => runAnalysis(false)}
                disabled={runningAction !== null}
                className="rounded border border-indigo-300 bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700 hover:bg-indigo-100 disabled:opacity-50"
              >
                {runningAction === 'normal' ? 'Analyzing…' : latest ? 'Re-analyze Funnel' : 'Analyze Funnel'}
              </button>
              {latest && (
                <button
                  type="button"
                  onClick={() => runAnalysis(true)}
                  disabled={runningAction !== null}
                  title="Runs the AI again from scratch even though the underlying numbers haven't changed since the last analysis."
                  className="text-xs font-medium text-zinc-500 underline decoration-dotted hover:text-zinc-700 disabled:opacity-50"
                >
                  {runningAction === 'force' ? 'Running fresh analysis…' : 'Run fresh analysis anyway'}
                </button>
              )}
            </div>
          )}
          {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
          {latest && <AnalysisResultView result={latest} />}

          {older.length > 0 && (
            <details className="mt-4">
              <summary className="cursor-pointer text-xs font-medium text-zinc-500 hover:text-zinc-700">
                Past analyses ({older.length})
              </summary>
              <ul className="mt-2 space-y-2">
                {older.map((entry, idx) => {
                  const expanded = expandedHistory.has(idx);
                  return (
                    <li key={entry.createdAt} className="rounded border border-zinc-200">
                      <button
                        type="button"
                        onClick={() => toggleHistoryRow(idx)}
                        className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-xs hover:bg-zinc-50"
                      >
                        <span className="text-zinc-500" title={new Date(entry.createdAt).toLocaleString('en-US')}>
                          {relativeTime(entry.createdAt)}
                        </span>
                        <span className="truncate text-zinc-700">{entry.topPriorityAction}</span>
                        <span className="shrink-0 text-zinc-400">{expanded ? '▲' : '▼'}</span>
                      </button>
                      {expanded && (
                        <div className="px-3 pb-3">
                          <AnalysisResultView result={entry} />
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </details>
          )}
        </div>
      )}
    </div>
  );
}

export default function FunnelView({
  initialData,
  slug,
  pages,
  role,
}: {
  initialData: FunnelDashboardData[];
  slug: string;
  pages: MarketingLandingPage[];
  role: Role;
}) {
  const [data, setData] = useState(initialData);
  const [trafficMode, setTrafficMode] = useState<TrafficMode>('ads');
  const [compareMode, setCompareMode] = useState(false);
  const [compareData, setCompareData] = useState<Record<string, FunnelDashboardData[]>>({});
  const [loadingCompare, setLoadingCompare] = useState(false);
  const [loadingMode, setLoadingMode] = useState(false);

  const currentName = pages.find((p) => p.slug === slug)?.name ?? slug;
  const otherPages = pages.filter((p) => p.slug !== slug);
  // The analyze endpoint is OWNER-only server-side (same convention as every other non-GET
  // marketing action except ad spend) — hide the button for ADS_MANAGER rather than showing it
  // and having every click 403/404.
  const canAnalyze = role === 'OWNER';

  async function loadCompareData(mode: TrafficMode) {
    if (otherPages.length === 0) return;
    setLoadingCompare(true);
    try {
      const results = await Promise.all(otherPages.map((p) => api.getMarketingFunnel(p.slug, mode)));
      const next: Record<string, FunnelDashboardData[]> = {};
      otherPages.forEach((p, i) => {
        next[p.slug] = results[i];
      });
      setCompareData(next);
    } finally {
      setLoadingCompare(false);
    }
  }

  async function toggleCompare() {
    if (!compareMode && Object.keys(compareData).length === 0) {
      await loadCompareData(trafficMode);
    }
    setCompareMode((v) => !v);
  }

  async function changeTrafficMode(mode: TrafficMode) {
    setTrafficMode(mode);
    setLoadingMode(true);
    try {
      const fresh = await api.getMarketingFunnel(slug, mode);
      setData(fresh);
      // Compare data was fetched for the old mode — invalidate and refetch if already showing.
      if (compareMode) {
        await loadCompareData(mode);
      } else {
        setCompareData({});
      }
    } finally {
      setLoadingMode(false);
    }
  }

  if (data.length === 0) {
    return (
      <div>
        <div className="mb-4">
          <TrafficModeToggle
            mode={trafficMode}
            onChange={changeTrafficMode}
            adsDescription="Only counting visitors, funnel steps, and bookings attributed to a paid Meta/Google ad click — the default, since mani runs ads."
            allDescription="Counting every visitor, funnel step, and booking regardless of traffic source, including organic and direct visits."
          />
        </div>
        <div className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No booking-funnel data recorded yet for this landing page{trafficMode === 'ads' ? ' under Ads only — try All traffic' : ''}.
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <TrafficModeToggle
          mode={trafficMode}
          onChange={changeTrafficMode}
          adsDescription="Only counting visitors, funnel steps, and bookings attributed to a paid Meta/Google ad click — the default, since mani runs ads. Bookings under Ads only are matched via contact-capture data and may slightly undercount."
          allDescription="Counting every visitor, funnel step, and booking regardless of traffic source, including organic and direct visits."
        />
        {otherPages.length > 0 && (
          <button
            type="button"
            onClick={toggleCompare}
            disabled={loadingCompare || loadingMode}
            className="rounded border border-zinc-300 px-3 py-1.5 text-sm text-zinc-700 hover:bg-zinc-50 disabled:opacity-50"
          >
            {loadingCompare
              ? 'Loading…'
              : compareMode
                ? 'Hide comparison'
                : `Compare with ${otherPages.map((p) => p.name).join(', ')}`}
          </button>
        )}
      </div>

      <div className={compareMode ? 'grid gap-4 lg:grid-cols-2' : 'space-y-4'}>
        {data.map((funnel) => (
          <FunnelPanel
            key={`${slug}-${funnel.flowKey}-${trafficMode}`}
            label={currentName}
            slug={slug}
            data={funnel}
            canAnalyze={canAnalyze}
            trafficMode={trafficMode}
          />
        ))}
        {compareMode &&
          otherPages.flatMap((p) =>
            (compareData[p.slug] ?? []).map((funnel) => (
              <FunnelPanel
                key={`${p.slug}-${funnel.flowKey}-${trafficMode}`}
                label={p.name}
                slug={p.slug}
                data={funnel}
                canAnalyze={canAnalyze}
                trafficMode={trafficMode}
              />
            )),
          )}
      </div>
    </div>
  );
}
