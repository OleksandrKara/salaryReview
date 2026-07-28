import type { TrafficSourceKey } from '../../lib/types';

export type { TrafficSourceKey };

export const ALL_TRAFFIC_SOURCES: TrafficSourceKey[] = [
  'meta_ads', 'google_ads', 'instagram_organic', 'google_organic', 'direct',
];

/** "Ads only" — the default everywhere: mani runs paid ads, so counting only paid-click traffic
 * is the more useful default view. */
export const ADS_ONLY_SOURCES: TrafficSourceKey[] = ['meta_ads', 'google_ads'];

export const SOURCE_LABELS: Record<TrafficSourceKey, string> = {
  meta_ads: 'Meta Ads',
  google_ads: 'Google Ads',
  instagram_organic: 'Instagram (organic)',
  google_organic: 'Google (organic)',
  direct: 'Direct',
};

/** Serializes a selection for the `sources` query param the backend expects — "all" when every
 * bucket is selected (matching the backend's own "byte-for-byte unfiltered, including edge cases
 * that fit none of the five buckets" behavior for that exact case), otherwise a comma list. */
export function sourcesParam(selected: Set<TrafficSourceKey>): string {
  return selected.size === ALL_TRAFFIC_SOURCES.length ? 'all' : Array.from(selected).join(',');
}

/** Shared multi-select traffic-source filter — used identically on the Overview, Contacts,
 * Analytics, and Funnel tabs, so switching tabs never changes what "Instagram (organic)" or
 * "Direct" means (see the backend's TrafficSourceSql for the shared classification). Each of the
 * five buckets is independently toggleable via its own chip — e.g. select just "Instagram
 * (organic)" alone to isolate bio-link traffic, or "Instagram (organic)" + "Direct" together for
 * "everything that isn't a paid click or Google" — plus two one-click presets for the common
 * cases: "Ads only" and "All traffic".
 */
export default function TrafficSourceFilter({
  selected,
  onChange,
  description,
  disabled,
}: {
  selected: Set<TrafficSourceKey>;
  onChange: (next: Set<TrafficSourceKey>) => void;
  /** Static, tab-specific hint about what this filters (e.g. "page views, clicks, contacts, and
   * bookings") — shown under the chips regardless of the current selection. */
  description: string;
  /** Disables every chip/preset while a change from this filter is still loading — without this,
   * a slow request (this data is often Square-backed and can take several seconds) leaves the
   * chips clickable, so a second or third click before the first request lands looks like nothing
   * happened and just queues up more redundant fetches. */
  disabled?: boolean;
}) {
  const isAdsOnly = selected.size === ADS_ONLY_SOURCES.length && ADS_ONLY_SOURCES.every((s) => selected.has(s));
  const isAll = selected.size === ALL_TRAFFIC_SOURCES.length;

  function toggle(key: TrafficSourceKey) {
    const next = new Set(selected);
    if (next.has(key)) {
      if (next.size === 1) return; // never allow an empty selection — nothing to show
      next.delete(key);
    } else {
      next.add(key);
    }
    onChange(next);
  }

  return (
    <div className={`rounded-lg p-3 ring-1 ring-zinc-200 ${disabled ? 'opacity-60' : ''}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-zinc-500">Traffic source</span>
        <div className="flex items-center gap-2 text-xs">
          <button
            type="button"
            onClick={() => onChange(new Set(ADS_ONLY_SOURCES))}
            disabled={isAdsOnly || disabled}
            className={isAdsOnly ? 'font-medium text-zinc-300' : 'font-medium text-blue-600 hover:underline disabled:pointer-events-none disabled:no-underline disabled:text-zinc-300'}
          >
            Ads only
          </button>
          <span className="text-zinc-300">·</span>
          <button
            type="button"
            onClick={() => onChange(new Set(ALL_TRAFFIC_SOURCES))}
            disabled={isAll || disabled}
            className={isAll ? 'font-medium text-zinc-300' : 'font-medium text-blue-600 hover:underline disabled:pointer-events-none disabled:no-underline disabled:text-zinc-300'}
          >
            All traffic
          </button>
        </div>
      </div>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {ALL_TRAFFIC_SOURCES.map((key) => {
          const active = selected.has(key);
          return (
            <button
              key={key}
              type="button"
              onClick={() => toggle(key)}
              disabled={disabled}
              aria-pressed={active}
              className={`rounded-full px-3 py-1.5 text-xs font-medium ring-1 transition-colors disabled:pointer-events-none ${
                active
                  ? 'bg-zinc-900 text-white ring-zinc-900'
                  : 'bg-white text-zinc-600 ring-zinc-300 hover:bg-zinc-50'
              }`}
            >
              {SOURCE_LABELS[key]}
            </button>
          );
        })}
      </div>
      <p className="mt-2 text-xs text-zinc-400">{description}</p>
    </div>
  );
}
