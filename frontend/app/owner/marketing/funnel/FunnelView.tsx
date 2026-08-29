'use client';

import { useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { api } from '../../../lib/api';
import type { FunnelAnalysisResult, FunnelDashboardData, MarketingLandingPage, Role, TrafficSourceKey } from '../../../lib/types';
import TrafficSourceFilter, { ADS_ONLY_SOURCES, ALL_TRAFFIC_SOURCES } from '../TrafficSourceFilter';
import PeriodFilter from '../PeriodFilter';
import { parsePeriodParams, periodToBounds } from '../period';
import type { PeriodSelection } from '../period';

/** The AI "Analyze Funnel" feature has its own, separate ads/all contract (see api.analyzeFunnel)
 * — it isn't part of this tab's 5-way filter. Selecting every bucket maps to "all"; anything
 * narrower (true ads-only, or an organic-only slice) maps to "ads", the closer of the two. */
function sourcesToAnalyzeMode(sources: Set<TrafficSourceKey>): 'ads' | 'all' {
  return sources.size === ALL_TRAFFIC_SOURCES.length ? 'all' : 'ads';
}

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

/** Friendly display names for flow_key — falls back to the raw key for anything unmapped (e.g.
 * akluxnails-home's homepage flow, or a future flow not listed here yet). Needed once a single
 * landing page can have more than one flow_key (mani's contact-first vs contact-last A/B test):
 * without this, two panels for the same page would be distinguished only by a small raw string,
 * reading as duplicate/confusing cards rather than two clearly-named experiments. */
const FLOW_KEY_LABELS: Record<string, string> = {
  mani_booking_v1: 'Contact info first',
  mani_booking_v2: 'Contact info last',
};

/** Friendly display names for step_key — same vocabulary is reused across differently-ordered
 * flows (only the order differs), so one map covers every flow. Falls back to the raw key. */
const STEP_KEY_LABELS: Record<string, string> = {
  contact: 'Contact info',
  services: 'Services',
  datetime: 'Date & time',
  confirm: 'Confirm & book',
};

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
  sources,
  completedSharedAcrossFlows,
}: {
  label: string;
  slug: string;
  data: FunnelDashboardData;
  canAnalyze: boolean;
  sources: Set<TrafficSourceKey>;
  /** True when this page has more than one flow_key — "Completed" is counted per landing page,
   * not per flow (see marketing.attribution, which has no flow_key column), so every flow panel
   * of a multi-flow page shows the same number. Surfaced as a tooltip rather than split per-flow,
   * since splitting it accurately isn't possible without a schema change to a live table. */
  completedSharedAcrossFlows: boolean;
}) {
  const retiredTitle = data.lastActivityAt
    ? `No new visitors since ${relativeTime(data.lastActivityAt)} — likely retired in favor of another variant. Data kept for history, not deleted.`
    : 'No activity recorded for this flow. Data kept for history, not deleted.';
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
    // parent remounts the whole panel (via its `key`) whenever the source selection changes, so
    // this only ever needs to run once per mount. loadingHistory already starts as `canAnalyze` (see
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
      await api.analyzeFunnel(slug, data.flowKey, sourcesToAnalyzeMode(sources), force);
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
    <div className={`rounded-lg border p-4 ${data.active ? 'border-zinc-200' : 'border-zinc-200 bg-zinc-50/60'}`}>
      <div className="flex items-center justify-between gap-2">
        <div>
          <h3 className={`font-medium ${data.active ? 'text-zinc-900' : 'text-zinc-500'}`}>{label}</h3>
          <p className="text-xs text-zinc-400" title={data.flowKey}>
            {FLOW_KEY_LABELS[data.flowKey] ?? data.flowKey}
          </p>
        </div>
        {!data.active && (
          <span
            title={retiredTitle}
            className="shrink-0 whitespace-nowrap rounded-full bg-zinc-200 px-2 py-0.5 text-xs font-medium text-zinc-600"
          >
            Retired
          </span>
        )}
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
          <dt className="text-xs text-zinc-500" title={completedSharedAcrossFlows ? 'Counted per landing page, shared across every flow shown below' : undefined}>
            Completed{completedSharedAcrossFlows ? ' (page total)' : ''}
          </dt>
          <dd className="font-semibold tabular-nums">{data.totalCompleted.toLocaleString('en-US')}</dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Conversion (of visitors)</dt>
          <dd className="font-semibold tabular-nums">{pct(data.finalConversionRate)}</dd>
        </div>
      </dl>

      <div className="mt-4 space-y-3">
        {data.steps.map((step, i) => {
          // dropOffCount is people who completed the PREVIOUS step but never advanced into this
          // one — the friction is whatever action separates the two (e.g. filling out and
          // submitting a contact form), not something inherently wrong with this step's own
          // content. Naming the previous step explicitly (rather than a bare "dropped off here")
          // keeps that direction unambiguous — index 0 never has a nonzero dropOffCount (there's
          // no earlier step to have abandoned), so prevStep is only read when it's safe to.
          const prevStep = i > 0 ? data.steps[i - 1] : null;
          return (
            <div key={step.stepKey}>
              <div className="flex items-center justify-between text-xs">
                <span className="font-medium text-zinc-700" title={step.stepKey}>
                  Step {step.stepIndex + 1} of {step.stepCountTotal}: {STEP_KEY_LABELS[step.stepKey] ?? step.stepKey}
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
                  −{step.dropOffCount.toLocaleString('en-US')} didn&apos;t complete
                  {prevStep ? ` "${STEP_KEY_LABELS[prevStep.stepKey] ?? prevStep.stepKey}"` : ' the previous step'} ({pct(step.dropOffPct)})
                </p>
              )}
            </div>
          );
        })}
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
  timeZone,
}: {
  initialData: FunnelDashboardData[];
  slug: string;
  pages: MarketingLandingPage[];
  role: Role;
  // Phase 6.3: the business's real configured timezone — see ../period's own doc.
  timeZone?: string;
}) {
  const searchParams = useSearchParams();
  const [data, setData] = useState(initialData);
  const [sources, setSources] = useState<Set<TrafficSourceKey>>(() => new Set(ADS_ONLY_SOURCES));
  // Defaults to 'all' (not the usual 'mtd') — a funnel's drop-off shape is normally read over its
  // whole history, not just the current month. See ../period's parsePeriodParams.
  const [selection, setSelection] = useState<PeriodSelection>(() => parsePeriodParams(searchParams, 'all'));
  const [compareMode, setCompareMode] = useState(false);
  const [compareData, setCompareData] = useState<Record<string, FunnelDashboardData[]>>({});
  const [loadingCompare, setLoadingCompare] = useState(false);
  const [loadingSources, setLoadingSources] = useState(false);

  const currentName = pages.find((p) => p.slug === slug)?.name ?? slug;
  const otherPages = pages.filter((p) => p.slug !== slug);
  // The analyze endpoint is OWNER-only server-side (same convention as every other non-GET
  // marketing action except ad spend) — hide the button for ADS_MANAGER rather than showing it
  // and having every click 403/404.
  const canAnalyze = role === 'OWNER';

  async function loadCompareData(nextSources: Set<TrafficSourceKey>, nextSelection: PeriodSelection) {
    if (otherPages.length === 0) return;
    setLoadingCompare(true);
    try {
      const bounds = periodToBounds(nextSelection, timeZone);
      const results = await Promise.all(
        otherPages.map((p) => api.getMarketingFunnel(p.slug, nextSources, bounds.from, bounds.to)),
      );
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
      await loadCompareData(sources, selection);
    }
    setCompareMode((v) => !v);
  }

  async function changeSources(next: Set<TrafficSourceKey>) {
    setSources(next);
    setLoadingSources(true);
    try {
      const bounds = periodToBounds(selection, timeZone);
      const fresh = await api.getMarketingFunnel(slug, next, bounds.from, bounds.to);
      setData(fresh);
      // Compare data was fetched for the old selection — invalidate and refetch if already showing.
      if (compareMode) {
        await loadCompareData(next, selection);
      } else {
        setCompareData({});
      }
    } finally {
      setLoadingSources(false);
    }
  }

  async function changePeriod(next: PeriodSelection) {
    setSelection(next);
    setLoadingSources(true);
    try {
      const bounds = periodToBounds(next, timeZone);
      const fresh = await api.getMarketingFunnel(slug, sources, bounds.from, bounds.to);
      setData(fresh);
      if (compareMode) {
        await loadCompareData(sources, next);
      } else {
        setCompareData({});
      }
    } finally {
      setLoadingSources(false);
    }
  }

  const sourcesKey = Array.from(sources).sort().join(',');
  // A retired flow (every variant that fed it has since had its weight zeroed/deactivated) is
  // tucked into a collapsed section below rather than shown side-by-side with the live one —
  // otherwise a frozen, no-longer-growing funnel reads as just another ongoing experiment.
  const activeFlows = data.filter((f) => f.active);
  const retiredFlows = data.filter((f) => !f.active);

  if (data.length === 0) {
    return (
      <div>
        <div className="mb-4">
          <TrafficSourceFilter
            selected={sources}
            onChange={changeSources}
            description="Counts visitors, funnel steps, and bookings for the selected source(s) only."
          />
        </div>
        <div className="mb-4">
          <PeriodFilter value={selection} onChange={changePeriod} timeZone={timeZone} />
        </div>
        <div className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No booking-funnel data recorded yet for this landing page under the selected source(s) — try All traffic.
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <TrafficSourceFilter
          selected={sources}
          onChange={changeSources}
          description="Counts visitors, funnel steps, and bookings for the selected source(s) only. Bookings are matched via contact-capture data and may slightly undercount."
        />
        {otherPages.length > 0 && (
          <button
            type="button"
            onClick={toggleCompare}
            disabled={loadingCompare || loadingSources}
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

      <div className="mb-4">
        <PeriodFilter value={selection} onChange={changePeriod} disabled={loadingSources} timeZone={timeZone} />
      </div>

      <div className={compareMode ? 'grid gap-4 lg:grid-cols-2' : 'space-y-4'}>
        {activeFlows.map((funnel) => (
          <FunnelPanel
            key={`${slug}-${funnel.flowKey}-${sourcesKey}`}
            label={currentName}
            slug={slug}
            data={funnel}
            canAnalyze={canAnalyze}
            sources={sources}
            completedSharedAcrossFlows={data.length > 1}
          />
        ))}
        {compareMode &&
          otherPages.flatMap((p) => {
            const pageFunnels = compareData[p.slug] ?? [];
            return pageFunnels.map((funnel) => (
              <FunnelPanel
                key={`${p.slug}-${funnel.flowKey}-${sourcesKey}`}
                label={p.name}
                slug={p.slug}
                data={funnel}
                canAnalyze={canAnalyze}
                sources={sources}
                completedSharedAcrossFlows={pageFunnels.length > 1}
              />
            ));
          })}
      </div>

      {retiredFlows.length > 0 && (
        <details className="mt-4" open={activeFlows.length === 0}>
          <summary className="cursor-pointer text-sm font-medium text-zinc-500 hover:text-zinc-700">
            Retired flows ({retiredFlows.length}) — no longer getting new traffic, kept for history
          </summary>
          <div className="mt-3 space-y-4">
            {retiredFlows.map((funnel) => (
              <FunnelPanel
                key={`${slug}-${funnel.flowKey}-${sourcesKey}`}
                label={currentName}
                slug={slug}
                data={funnel}
                canAnalyze={canAnalyze}
                sources={sources}
                completedSharedAcrossFlows={data.length > 1}
              />
            ))}
          </div>
        </details>
      )}
    </div>
  );
}
