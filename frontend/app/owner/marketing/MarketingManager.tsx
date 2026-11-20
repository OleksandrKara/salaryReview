'use client';

import { useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { api } from '../../lib/api';
import type { MarketingConversionsData, MarketingVariantStat, TrafficSourceKey } from '../../lib/types';
import TrafficSourceFilter, { ADS_ONLY_SOURCES } from './TrafficSourceFilter';
import PeriodFilter from './PeriodFilter';
import { parsePeriodParams, periodToBounds } from './period';
import type { PeriodSelection } from './period';
import VariantTable from './VariantTable';
import OverviewSummary from './OverviewSummary';

export default function MarketingManager({
  slug,
  initialVariants,
  initialConversions,
  readOnly = false,
  timeZone,
}: {
  slug: string;
  initialVariants: MarketingVariantStat[];
  initialConversions: MarketingConversionsData | null;
  readOnly?: boolean;
  // Phase 6.3: the business's real configured timezone, from BusinessSettingsDto — see page.tsx.
  timeZone?: string;
}) {
  const searchParams = useSearchParams();
  const [variants, setVariants] = useState(initialVariants);
  const [conversions, setConversions] = useState(initialConversions);
  const [loading, setLoading] = useState(false);
  const requestId = useRef(0);
  const [error, setError] = useState('');
  const [busyVariantId, setBusyVariantId] = useState<string | null>(null);
  const [sources, setSources] = useState<Set<TrafficSourceKey>>(() => new Set(ADS_ONLY_SOURCES));
  // Seeded from the URL (?period=&from=&to=) — see PeriodFilter/../period, shared by every
  // marketing tab. Defaults to 'all' (like Funnel, unlike most tabs' 'mtd') — layered on top of,
  // never replacing, the permanent "Hide stats before" cutoff below, which the backend intersects
  // with this itself.
  const [selection, setSelection] = useState<PeriodSelection>(() => parsePeriodParams(searchParams, 'all'));

  async function refresh(nextSources: Set<TrafficSourceKey> = sources, nextSelection: PeriodSelection = selection) {
    const bounds = periodToBounds(nextSelection, timeZone);
    const id = ++requestId.current;
    setLoading(true);
    try {
      const [data, conv] = await Promise.all([
        api.getMarketingDashboard(slug, nextSources, bounds.from, bounds.to),
        api.getMarketingConversions(slug, bounds.from, bounds.to).catch(() => null),
      ]);
      if (id !== requestId.current) return;
      setVariants(data.variants);
      setConversions(conv);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }

  function changeSources(next: Set<TrafficSourceKey>) {
    setSources(next);
    void refresh(next, selection);
  }

  function changePeriod(next: PeriodSelection) {
    setSelection(next);
    void refresh(sources, next);
  }

  async function withVariantBusy(v: MarketingVariantStat, action: () => Promise<void>) {
    setError('');
    setBusyVariantId(v.variantId);
    try {
      await action();
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setBusyVariantId(null);
    }
  }

  function onRename(v: MarketingVariantStat) {
    const name = prompt('New name for this variant:', v.name);
    if (!name || !name.trim() || name.trim() === v.name) return;
    return withVariantBusy(v, () => api.renameMarketingVariant(v.variantId, name.trim()));
  }

  function onEditDescription(v: MarketingVariantStat) {
    const description = prompt(
      "What is this variant testing and why? (e.g. hooks/offers, colors, headline) Leave blank to clear.",
      v.description ?? ''
    );
    if (description === null || description === v.description) return;
    return withVariantBusy(v, () => api.setMarketingVariantDescription(v.variantId, description.trim()));
  }

  function onDuplicate(v: MarketingVariantStat) {
    const name = prompt('Name for the copy:', `${v.name} (copy)`);
    if (!name || !name.trim()) return;
    return withVariantBusy(v, () => api.duplicateMarketingVariant(v.variantId, name.trim()).then(() => undefined));
  }

  function onDelete(v: MarketingVariantStat) {
    if (v.pageViews > 0 || v.conversions > 0) {
      if (!confirm(`"${v.name}" has ${v.pageViews} page view(s) and ${v.conversions} conversion(s) recorded — deletion will likely be blocked. Try anyway?`)) return;
    } else if (!confirm(`Delete "${v.name}"? This cannot be undone.`)) {
      return;
    }
    return withVariantBusy(v, () => api.deleteMarketingVariant(v.variantId));
  }

  return (
    <div className="space-y-6">
      {error && <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-red-200">{error}</p>}

      <div className="space-y-3">
        <PeriodFilter value={selection} onChange={changePeriod} timeZone={timeZone} disabled={loading} />
      </div>

      {conversions?.available && <OverviewSummary data={conversions} loading={loading} />}

      {variants.length > 1 && (
      <section aria-label="Variant performance" className={`space-y-3 transition-opacity ${loading ? 'opacity-50' : ''}`}>
        <div>
          <h2 className="text-sm font-semibold text-zinc-900">Variant performance</h2>
          <p className="mt-0.5 text-xs text-zinc-500">Which version of the page works best for the selected sources and period.</p>
        </div>
        <TrafficSourceFilter
          selected={sources}
          onChange={changeSources}
          disabled={loading}
          description="Filters the variant table only."
        />
        <VariantTable
          variants={variants}
          onRename={onRename}
          onEditDescription={onEditDescription}
          onDuplicate={onDuplicate}
          onDelete={onDelete}
          busyVariantId={busyVariantId}
          readOnly={readOnly}
        />
      </section>
      )}
    </div>
  );
}
