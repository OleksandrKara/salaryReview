import Link from 'next/link';

// The PMU business has two sites on one domain (2026-10-14): the studio and the academy
// (/academy/, formerly edu.pmu-annakara.com). Shown on the SEO and AI tabs when the business has
// the "academy" landing page; the backend scopes the numbers by ?site= (see SeoSite).
export const SITES = [
  { key: 'studio', label: 'Studio' },
  { key: 'academy', label: 'Academy' },
  { key: 'all', label: 'All' },
] as const;

/** The selected site for a page with the switch, or undefined when the business has no academy. */
export function resolveSite(hasAcademy: boolean, requested: string | undefined): string | undefined {
  if (!hasAcademy) return undefined;
  return SITES.some((x) => x.key === requested) ? requested : 'studio';
}

export default function SiteSwitch({ basePath, site }: { basePath: string; site: string }) {
  return (
    <div className="mt-4 inline-flex rounded-lg bg-zinc-100 p-1 text-sm" role="tablist" aria-label="Site">
      {SITES.map((x) => (
        <Link
          key={x.key}
          href={`${basePath}?site=${x.key}`}
          prefetch={false}
          role="tab"
          aria-selected={site === x.key}
          className={`rounded-md px-3 py-1.5 font-medium ${site === x.key ? 'bg-white text-zinc-900 shadow-sm' : 'text-zinc-500 hover:text-zinc-800'}`}
        >
          {x.label}
        </Link>
      ))}
    </div>
  );
}
