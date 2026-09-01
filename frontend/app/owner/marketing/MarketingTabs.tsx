'use client';

import Link, { useLinkStatus } from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState, useTransition } from 'react';
import { api } from '../../lib/api';
import type { MarketingLandingPage } from '../../lib/types';
import { Spinner } from '../../components/Spinner';

const BASE_TABS = [
  { href: '/owner/marketing', label: 'Overview' },
  { href: '/owner/marketing/contacts', label: 'Contacts' },
  { href: '/owner/marketing/funnel', label: 'Funnel' },
  { href: '/owner/marketing/ads-report', label: 'Ads Report' },
  { href: '/owner/marketing/ltv', label: 'LTV' },
];
const SEO_TAB = { href: '/owner/marketing/seo', label: 'SEO' };

// Also the default landing page every marketing page.tsx server component should scope its own
// data fetch to when ?slug= is absent — mani is the only page with real ad spend/history (see
// openspec/changes/ads-report-consolidation/design.md), and this selector already visually shows
// it pre-selected in that case, so the fetched data has to actually match what's shown as selected.
export const DEFAULT_SLUG = 'mani';

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
// currently active, plus the "Sync appointments" button (moved here from the Contacts tab so it's
// one click away regardless of which marketing tab is open — Ads Report's follow-up numbers are
// only as fresh as the last sync, same staleness contract Overview's "+N follow-up" line already
// has). Overview and Ads Report both read ?slug= server-side to scope their data; Contacts pools
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
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState<string | null>(null);
  // undefined = not yet fetched (renders nothing); null = fetched, never synced; string = fetched,
  // has a real timestamp — three distinct states, so "haven't checked yet" never flashes as
  // "Never synced yet." for a page whose real answer is a moment away.
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null | undefined>(undefined);
  // design.md D6: the SEO tab is hidden entirely (not just disabled) for a business that hasn't
  // turned the feature on — defaults to hidden while this hasn't resolved yet, same "don't flash
  // something that might not apply" reasoning as lastSyncedAt's three-state handling below.
  const [seoMonitoringEnabled, setSeoMonitoringEnabled] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.getMarketingPages().then((p) => { if (!cancelled) setPages(p); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    api.getMe().then((me) => { if (!cancelled) setSeoMonitoringEnabled(me.features.seoMonitoringEnabled); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const TABS = seoMonitoringEnabled ? [...BASE_TABS, SEO_TAB] : BASE_TABS;

  // Independent of the pages fetch above — a failure here shouldn't block the page selector, and
  // vice versa. Cheap (a single DB row on the backend), safe to fetch on every mount.
  useEffect(() => {
    let cancelled = false;
    api.getMarketingSyncStatus().then((s) => { if (!cancelled) setLastSyncedAt(s.lastSyncedAt); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const currentSlug = searchParams.get('slug') ?? DEFAULT_SLUG;

  // Preserves every other param already on the URL (period/from/to, most importantly — see
  // PeriodFilter) rather than rebuilding the query string from just slug, which would otherwise
  // silently reset the owner's currently-selected period every time they switch pages or tabs.
  function hrefWithSlug(pathnameOrTab: string, slug: string): string {
    const params = new URLSearchParams(searchParams.toString());
    if (slug === DEFAULT_SLUG) params.delete('slug'); else params.set('slug', slug);
    const qs = params.toString();
    return qs ? `${pathnameOrTab}?${qs}` : pathnameOrTab;
  }

  function selectPage(slug: string) {
    startPageChangeTransition(() => { router.push(hrefWithSlug(pathname, slug)); });
  }

  // Resolves any lead that never linked to a Square customer through the tracked booking flow (a
  // manager followed up and booked them by phone, or they came back through some other channel)
  // and refreshes appointment/no-show/cancelled status for everyone else too — see
  // MarketingContactsService.syncSquareLinks. This component doesn't hold the contacts/ads-report
  // data itself (it's a sibling of each tab's content, not a parent), so a plain router.refresh()
  // re-runs whichever page is currently mounted server-side; that page's client view re-syncs its
  // own local state from the fresh initialData prop (see e.g. ContactsFilterBar's/AdsReportView's
  // useEffect(() => setX(initialX), [initialX])) rather than silently keeping stale numbers.
  async function syncAppointments() {
    setSyncing(true);
    setSyncMessage(null);
    try {
      await api.syncMarketingContacts();
      router.refresh();
      // Optimistic — the backend recorded its own Instant.now() during that same request, close
      // enough for a "last synced" display to not need a second round trip just to re-read it.
      setLastSyncedAt(new Date().toISOString());
      setSyncMessage('✓ Synced — appointment data refreshed.');
    } catch (e) {
      setSyncMessage(e instanceof Error ? `Sync failed: ${e.message}` : 'Sync failed. Please try again.');
    } finally {
      setSyncing(false);
      setTimeout(() => setSyncMessage(null), 6000);
    }
  }

  return (
    <div className="mb-6 border-b border-zinc-200 pb-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {/* overflow-x-auto (not flex-wrap): a horizontally-scrollable tab strip is the standard
            mobile pattern here, rather than wrapping tabs onto a second line. */}
        <div className="flex min-w-0 gap-1 overflow-x-auto border-b border-transparent">
          {TABS.map((tab) => {
            const active = pathname === tab.href;
            const href = hrefWithSlug(tab.href, currentSlug);
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
          <label className="flex items-center gap-2 text-xs text-zinc-500">
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

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={syncAppointments}
          disabled={syncing}
          title="Look up Square for any lead that booked without going through the tracked flow (a manager who followed up by phone, or a client who came back on their own), and refresh everyone's appointment/no-show/cancelled status"
          className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {syncing ? (
            <Spinner className="h-4 w-4 text-white" />
          ) : (
            <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v6h-6" />
            </svg>
          )}
          {syncing ? 'Syncing appointments…' : 'Sync appointments'}
        </button>
        {syncMessage ? (
          <span className={`text-sm ${syncMessage.startsWith('Sync failed') ? 'text-red-600' : 'text-zinc-600'}`}>
            {syncMessage}
          </span>
        ) : (
          <span className="text-xs text-zinc-400">
            Finds bookings/no-shows/cancellations for leads who converted outside the online booking flow.
          </span>
        )}
      </div>
      <p className="mt-1 text-xs text-zinc-400">{fmtLastSyncedAt(lastSyncedAt)}</p>
    </div>
  );
}

/** "Last synced Jul 22 at 2:30 PM" — same absolute-time convention SyncBadge already uses
 * elsewhere in the app, rather than a relative "5 minutes ago" that would need a ticking timer to
 * stay accurate on a page left open a while. Blank (not an error message) while the initial fetch
 * is still in flight, so it never flashes "Never synced yet" for a page that actually has a
 * real timestamp arriving a moment later. */
function fmtLastSyncedAt(iso: string | null | undefined): string {
  if (iso === undefined) return '';
  if (iso === null) return 'Never synced yet.';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  return `Last synced ${date} at ${time}`;
}
