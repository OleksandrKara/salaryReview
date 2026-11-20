'use client';

import Link, { useLinkStatus } from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState, useTransition } from 'react';
import { api } from '../../lib/api';
import type { MarketingLandingPage } from '../../lib/types';
import { Spinner } from '../../components/Spinner';
import { firstActiveSlug, pageOptionLabel } from '../../lib/marketingPages';

const BASE_TABS = [
  { href: '/owner/marketing', label: 'Overview' },
  { href: '/owner/marketing/contacts', label: 'Contacts' },
  { href: '/owner/marketing/funnel', label: 'Funnel' },
  { href: '/owner/marketing/ads-report', label: 'Ads Report' },
  { href: '/owner/marketing/google-ppc', label: 'Google PPC' },
  { href: '/owner/marketing/ltv', label: 'LTV' },
];
const LOST_TAB = { href: '/owner/marketing/lost-report', label: 'Lost leads' };
const SEO_TAB = { href: '/owner/marketing/seo', label: 'SEO' };
const AI_TAB = { href: '/owner/marketing/ai', label: 'AI' };
// Business-wide tabs (one domain per business, not per landing page): no ?slug=, no page selector.
const BUSINESS_WIDE = new Set([SEO_TAB.href, AI_TAB.href, LOST_TAB.href, '/owner/marketing/google-ppc']);

/** The Ads Manager (PPC agency) sees Google PPC and the lost-leads report, nothing else. */
const ADS_MANAGER_TABS = [BASE_TABS.find((t) => t.href === '/owner/marketing/google-ppc')!, LOST_TAB];

// Also the default landing page every marketing page.tsx server component should scope its own
// data fetch to when ?slug= is absent — mani is the only page with real ad spend/history (see
// openspec/changes/ads-report-consolidation/design.md), and this selector already visually shows
// it pre-selected in that case, so the fetched data has to actually match what's shown as selected.
export const DEFAULT_SLUG = 'mani';

/** Contacts tab's "every site" choice; never sent as ?slug= (absent slug means all there). */
const ALL_SITES = '__all';

// Rendered as a child of each tab's <Link> — useLinkStatus only works inside a Link's own subtree.
// Needed specifically because these links set prefetch={false} (hovering a tab would otherwise
// fire the same expensive Square-backed fetch the destination tab needs just to preview it): the
// route's own loading.tsx fallback is normally shown via a prefetched "instant" path, which without
// prefetch can be inconsistent about appearing right on click — this is Next's own documented fix
// for exactly that gap, giving a second, always-reliable "your click registered" signal.
function TabPendingSpinner() {
  const { pending } = useLinkStatus();
  if (!pending) return null;
  return <Spinner className="h-3.5 w-3.5" />;
}

// A second, in-page way to switch between Marketing's views — the main nav menu only ever links
// to /owner/marketing itself, so without this Contacts/Funnel/Ads Report are reachable only by
// URL. Also hosts the landing-page selector once more than one page exists (see
// marketing.landing_pages), shown on every tab and carrying ?slug= along whichever tab is
// currently active. Overview and Ads Report both read ?slug= server-side to scope their data; Contacts pools
// every page's data client-side regardless (see ContactsFilterBar) — there, the selector just
// pre-populates its own "Landing page" facet rather than changing what's fetched.
export default function MarketingTabs() {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [pages, setPages] = useState<MarketingLandingPage[]>([]);
  // The selector below is a <select>, not a <Link> — useLinkStatus doesn't apply, so its own
  // pending feedback comes from useTransition instead. Same reasoning as the tabs: switching the
  // landing page re-fetches every tab's (often Square-backed, multi-second) data from scratch.
  const [pageChangePending, startPageChangeTransition] = useTransition();
  // design.md D6: the SEO tab is hidden entirely (not just disabled) for a business that hasn't
  // turned the feature on — defaults to hidden while this hasn't resolved yet, so it doesn't flash
  // something that might not apply".
  const [seoMonitoringEnabled, setSeoMonitoringEnabled] = useState(false);
  const [aiVisibilityEnabled, setAiVisibilityEnabled] = useState(false);
  // The Ads Manager (PPC agency) gets a shorter tab list, so the bar stays hidden (keeping its
  // height) until /api/me says which role this is.
  const [role, setRole] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getMarketingPages().then((p) => { if (!cancelled) setPages(p); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    api.getMe().then((me) => {
      if (cancelled) return;
      setRole(me.role);
      setSeoMonitoringEnabled(me.features.seoMonitoringEnabled);
      setAiVisibilityEnabled(me.features.aiVisibilityEnabled);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const extra = [...(seoMonitoringEnabled ? [SEO_TAB] : []), ...(aiVisibilityEnabled ? [AI_TAB] : [])];
  const TABS = role === 'ADS_MANAGER' ? ADS_MANAGER_TABS : [...BASE_TABS, ...extra];

  // Contacts pools every site's leads, so it defaults to (and offers) "All sites" (owner request
  // 2026-10-12: the selector showed one site while the table listed every site's contacts). The
  // other tabs need one page and keep DEFAULT_SLUG.
  const isContactsTab = pathname === '/owner/marketing/contacts';
  // Matches the backend default (its first active page); falls back to DEFAULT_SLUG until pages load.
  const defaultSlug = firstActiveSlug(pages) ?? DEFAULT_SLUG;
  const currentSlug = searchParams.get('slug') ?? (isContactsTab ? ALL_SITES : defaultSlug);

  // Preserves every other param already on the URL (period/from/to, most importantly — see
  // PeriodFilter) rather than rebuilding the query string from just slug, which would otherwise
  // silently reset the owner's currently-selected period every time they switch pages or tabs.
  function hrefWithSlug(pathnameOrTab: string, slug: string): string {
    const params = new URLSearchParams(searchParams.toString());
    // Tab links: "All sites" only exists on Contacts, so leaving it drops back to each tab's default.
    if (slug === defaultSlug || slug === ALL_SITES) params.delete('slug'); else params.set('slug', slug);
    const qs = params.toString();
    return qs ? `${pathnameOrTab}?${qs}` : pathnameOrTab;
  }

  function selectPage(slug: string) {
    startPageChangeTransition(() => { router.push(hrefWithSlug(pathname, slug)); });
  }

  return (
    <div className="mb-6 border-b border-zinc-200 pb-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {/* Owner request 2026-10-12: every tab visible on a phone without sideways scrolling. Below
            sm the tabs form a 4-column grid (7 tabs = 2 rows); from sm up a single wrapping row. */}
        <nav aria-label="Marketing sections" className={`${role === null ? 'invisible ' : ''}grid w-full ${role === 'ADS_MANAGER' ? 'grid-cols-2' : 'grid-cols-4'} gap-1 sm:flex sm:w-auto sm:flex-wrap`}>
          {TABS.map((tab) => {
            const active = pathname === tab.href;
            // design.md D6: SEO is scoped to the business's one primary domain, not a per-landing-
            // page dimension — its own link never carries ?slug=, unlike every other tab here.
            // Contacts always opens on "All sites" (owner request 2026-10-12); the Site selector
            // there narrows it afterwards.
            const href = BUSINESS_WIDE.has(tab.href) ? tab.href
              : tab.href === '/owner/marketing/contacts' ? hrefWithSlug(tab.href, ALL_SITES)
              : hrefWithSlug(tab.href, currentSlug);
            return (
              <Link
                key={tab.href}
                href={href}
                prefetch={false}
                aria-current={active ? 'page' : undefined}
                className={`flex min-h-[44px] items-center justify-center gap-1.5 whitespace-nowrap rounded-md px-1 py-2 text-[13px] font-medium transition-colors sm:min-h-0 sm:justify-start sm:rounded-none sm:border-b-2 sm:px-3 sm:text-sm ${
                  active ? 'bg-zinc-900 sm:border-zinc-900 sm:bg-transparent' : 'bg-zinc-100 sm:border-transparent sm:bg-transparent'
                }`}
              >
                {/* Color on a span: landing.css's unlayered `a { color: inherit }` beats utility classes on the <a>. */}
                <span className={active ? 'text-white sm:text-zinc-900' : 'text-zinc-600 hover:text-zinc-900 sm:text-zinc-500 sm:hover:text-zinc-700'}>{tab.label}</span>
                <TabPendingSpinner />
              </Link>
            );
          })}
        </nav>
        {/* design.md D6: the landing-page selector is a different dimension from SEO (which
            tracks one domain via a single seo_connection per business, not per landing page) —
            hidden on the SEO tab so it never implies picking a page changes what's shown there. */}
        {pages.length > 1 && !BUSINESS_WIDE.has(pathname) && (
          <label className="flex items-center gap-2 text-xs text-zinc-500">
            {isContactsTab ? 'Site' : 'Page'}
            <select
              value={currentSlug}
              onChange={(e) => selectPage(e.target.value)}
              disabled={pageChangePending}
              className="rounded border border-zinc-300 px-2 py-1 text-sm text-zinc-700 disabled:opacity-60"
            >
              {isContactsTab && <option value={ALL_SITES}>All sites ({pages.length})</option>}
              {pages.map((p) => (
                <option key={p.slug} value={p.slug}>{pageOptionLabel(p)}</option>
              ))}
            </select>
            {pageChangePending && <Spinner className="h-4 w-4 text-zinc-400" />}
          </label>
        )}
      </div>

    </div>
  );
}
