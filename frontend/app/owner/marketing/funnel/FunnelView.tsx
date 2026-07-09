'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import type { FunnelAnalysisResult, FunnelDashboardData, MarketingLandingPage, Role } from '../../../lib/types';

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

const IMPACT_STYLES: Record<string, string> = {
  HIGH: 'bg-red-50 text-red-700 ring-red-200',
  MEDIUM: 'bg-amber-50 text-amber-700 ring-amber-200',
  LOW: 'bg-zinc-100 text-zinc-600 ring-zinc-200',
};

function AnalysisResultView({ result }: { result: FunnelAnalysisResult }) {
  return (
    <div className="mt-4 space-y-4 rounded-lg border border-indigo-100 bg-indigo-50/40 p-4">
      <div>
        <p className="text-xs font-semibold uppercase tracking-wide text-indigo-700">Biggest bottleneck: {result.biggestBottleneckStep}</p>
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
}: {
  label: string;
  slug: string;
  data: FunnelDashboardData;
  canAnalyze: boolean;
}) {
  const [analysis, setAnalysis] = useState<FunnelAnalysisResult | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function analyze() {
    setAnalyzing(true);
    setError(null);
    try {
      const result = await api.analyzeFunnel(slug, data.flowKey);
      setAnalysis(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Analysis failed.');
    } finally {
      setAnalyzing(false);
    }
  }

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
          <button
            type="button"
            onClick={analyze}
            disabled={analyzing}
            className="rounded border border-indigo-300 bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700 hover:bg-indigo-100 disabled:opacity-50"
          >
            {analyzing ? 'Analyzing…' : analysis ? 'Re-analyze Funnel' : 'Analyze Funnel'}
          </button>
          {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
          {analysis && <AnalysisResultView result={analysis} />}
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
  const [compareMode, setCompareMode] = useState(false);
  const [compareData, setCompareData] = useState<Record<string, FunnelDashboardData[]>>({});
  const [loadingCompare, setLoadingCompare] = useState(false);

  const currentName = pages.find((p) => p.slug === slug)?.name ?? slug;
  const otherPages = pages.filter((p) => p.slug !== slug);
  // The analyze endpoint is OWNER-only server-side (same convention as every other non-GET
  // marketing action except ad spend) — hide the button for ADS_MANAGER rather than showing it
  // and having every click 403/404.
  const canAnalyze = role === 'OWNER';

  async function toggleCompare() {
    if (!compareMode && otherPages.length > 0 && Object.keys(compareData).length === 0) {
      setLoadingCompare(true);
      try {
        const results = await Promise.all(otherPages.map((p) => api.getMarketingFunnel(p.slug)));
        const next: Record<string, FunnelDashboardData[]> = {};
        otherPages.forEach((p, i) => {
          next[p.slug] = results[i];
        });
        setCompareData(next);
      } finally {
        setLoadingCompare(false);
      }
    }
    setCompareMode((v) => !v);
  }

  if (initialData.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
        No booking-funnel data recorded yet for this landing page.
      </div>
    );
  }

  return (
    <div>
      {otherPages.length > 0 && (
        <div className="mb-4 flex justify-end">
          <button
            type="button"
            onClick={toggleCompare}
            disabled={loadingCompare}
            className="rounded border border-zinc-300 px-3 py-1.5 text-sm text-zinc-700 hover:bg-zinc-50 disabled:opacity-50"
          >
            {loadingCompare
              ? 'Loading…'
              : compareMode
                ? 'Hide comparison'
                : `Compare with ${otherPages.map((p) => p.name).join(', ')}`}
          </button>
        </div>
      )}

      <div className={compareMode ? 'grid gap-4 lg:grid-cols-2' : 'space-y-4'}>
        {initialData.map((funnel) => (
          <FunnelPanel key={`${slug}-${funnel.flowKey}`} label={currentName} slug={slug} data={funnel} canAnalyze={canAnalyze} />
        ))}
        {compareMode &&
          otherPages.flatMap((p) =>
            (compareData[p.slug] ?? []).map((funnel) => (
              <FunnelPanel key={`${p.slug}-${funnel.flowKey}`} label={p.name} slug={p.slug} data={funnel} canAnalyze={canAnalyze} />
            )),
          )}
      </div>
    </div>
  );
}
