'use client';

import Link, { useLinkStatus } from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState, useTransition } from 'react';
import { api } from '../../lib/api';
import type { MarketingLandingPage } from '../../lib/types';
import { Spinner } from '../../components/Spinner';

const TABS = [
  { href: '/owner/marketing', label: 'Overview' },
  { href: '/owner/marketing/contacts', label: 'Contacts' },
  { href: '/owner/marketing/analytics', label: 'Analytics' },
  { href: '/owner/marketing/funnel', label: 'Funnel' },
  { href: '/owner/marketing/ads-report', label: 'Ads Report' },
];

const DEFAULT_SLUG = 'mani';

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
// to /owner/marketing itself, so without this Contacts/Analytics are reachable only by URL. Also
// hosts the landing-page selector once more than one page exists (see marketing.landing_pages),
// shown on all three tabs and carrying ?slug= along whichever tab is currently active. Overview
// and Analytics both read ?slug= server-side to scope their data; Contacts pools every page's
// data client-side regardless (see ContactsFilterBar) — there, the selector just pre-populates
// its own "Landing page" facet rather than changing what's fetched.
export default function MarketingTabs() {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [pages, setPages] = useState<MarketingLandingPage[]>([]);
  // The selector below is a <select>, not a <Link> — useLinkStatus doesn't apply, so its own
  // pending feedback comes from useTransition instead. Same reasoning as the tabs: switching the
  // landing page re-fetches every tab's (often Square-backed, multi-second) data from scratch.
  const [pageChangePending, startPageChangeTransition] = useTransition();

  useEffect(() => {
    let cancelled = false;
    api.getMarketingPages().then((p) => { if (!cancelled) setPages(p); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const currentSlug = searchParams.get('slug') ?? DEFAULT_SLUG;

  function selectPage(slug: string) {
    const href = slug === DEFAULT_SLUG ? pathname : `${pathname}?slug=${encodeURIComponent(slug)}`;
    startPageChangeTransition(() => { router.push(href); });
  }

  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200">
      {/* overflow-x-auto (not flex-wrap): now 5 tabs, this row no longer reliably fits a narrow
          phone screen — a horizontally-scrollable tab strip is the standard mobile pattern here,
          rather than wrapping tabs onto a second line. */}
      <div className="flex min-w-0 gap-1 overflow-x-auto">
        {TABS.map((tab) => {
          const active = pathname === tab.href;
          const href = currentSlug !== DEFAULT_SLUG ? `${tab.href}?slug=${encodeURIComponent(currentSlug)}` : tab.href;
          return (
            <Link
              key={tab.href}
              href={href}
              prefetch={false}
              aria-current={active ? 'page' : undefined}
              className={`flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
                active ? 'border-zinc-900 text-zinc-900' : 'border-transparent text-zinc-500 hover:text-zinc-700'
              }`}
            >
              {tab.label}
              <TabPendingSpinner />
            </Link>
          );
        })}
      </div>
      {pages.length > 1 && (
        <label className="mb-1.5 flex items-center gap-2 text-xs text-zinc-500">
          Page
          <select
            value={currentSlug}
            onChange={(e) => selectPage(e.target.value)}
            disabled={pageChangePending}
            className="rounded border border-zinc-300 px-2 py-1 text-sm text-zinc-700 disabled:opacity-60"
          >
            {pages.map((p) => (
              <option key={p.slug} value={p.slug}>{p.name}</option>
            ))}
          </select>
          {pageChangePending && <Spinner className="h-4 w-4 text-zinc-400" />}
        </label>
      )}
    </div>
  );
}
