export type TrafficMode = 'ads' | 'all';

/** Shared "Ads only / All traffic" control — used on the Overview, Analytics, and Funnel tabs.
 * "Ads only" is the default everywhere: mani runs paid ads, so counting only paid-click traffic
 * is the more useful default view; "All traffic" additionally includes organic/direct visits,
 * which matters more for pages like the homepage that aren't ad-funded.
 */
export default function TrafficModeToggle({
  mode,
  onChange,
  adsDescription,
  allDescription,
}: {
  mode: TrafficMode;
  onChange: (m: TrafficMode) => void;
  adsDescription: string;
  allDescription: string;
}) {
  return (
    <div className="rounded-lg p-3 ring-1 ring-zinc-200">
      <span className="text-xs font-medium uppercase tracking-wide text-zinc-500">Traffic</span>
      <div className="mt-2 inline-flex w-full flex-wrap gap-1 rounded-lg bg-zinc-100 p-1 sm:w-auto">
        <button
          type="button"
          onClick={() => onChange('ads')}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors sm:flex-none ${
            mode === 'ads' ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200' : 'text-zinc-500 hover:text-zinc-700'
          }`}
        >
          Ads only
        </button>
        <button
          type="button"
          onClick={() => onChange('all')}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors sm:flex-none ${
            mode === 'all' ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200' : 'text-zinc-500 hover:text-zinc-700'
          }`}
        >
          All traffic
        </button>
      </div>
      <p className="mt-1 text-xs text-zinc-400">{mode === 'ads' ? adsDescription : allDescription}</p>
    </div>
  );
}
