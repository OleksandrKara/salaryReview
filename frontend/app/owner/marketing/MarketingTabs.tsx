'use client';

import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import type { MarketingLandingPage } from '../../lib/types';

const TABS = [
  { href: '/owner/marketing', label: 'Overview' },
  { href: '/owner/marketing/contacts', label: 'Contacts' },
  { href: '/owner/marketing/analytics', label: 'Analytics' },
  { href: '/owner/marketing/funnel', label: 'Funnel' },
];

const DEFAULT_SLUG = 'mani';

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

  useEffect(() => {
    let cancelled = false;
    api.getMarketingPages().then((p) => { if (!cancelled) setPages(p); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const currentSlug = searchParams.get('slug') ?? DEFAULT_SLUG;

  function selectPage(slug: string) {
    router.push(slug === DEFAULT_SLUG ? pathname : `${pathname}?slug=${encodeURIComponent(slug)}`);
  }

  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200">
      <div className="flex gap-1">
        {TABS.map((tab) => {
          const active = pathname === tab.href;
          const href = currentSlug !== DEFAULT_SLUG ? `${tab.href}?slug=${encodeURIComponent(currentSlug)}` : tab.href;
          return (
            <Link
              key={tab.href}
              href={href}
              prefetch={false}
              aria-current={active ? 'page' : undefined}
              className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
                active ? 'border-zinc-900 text-zinc-900' : 'border-transparent text-zinc-500 hover:text-zinc-700'
              }`}
            >
              {tab.label}
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
            className="rounded border border-zinc-300 px-2 py-1 text-sm text-zinc-700"
          >
            {pages.map((p) => (
              <option key={p.slug} value={p.slug}>{p.name}</option>
            ))}
          </select>
        </label>
      )}
    </div>
  );
}
