import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import FunnelView from './FunnelView';
import { parsePeriodParams, periodToBounds } from '../period';

export default async function MarketingFunnelPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string; period?: string; from?: string; to?: string }>;
}) {
  const params = await searchParams;
  const { slug } = params;
  // getBusinessSettings is OWNER-only (403 for ADS_MANAGER) — fails open, same reasoning as the
  // Overview tab's own page.tsx: an ADS_MANAGER still gets the funnel, just falling back to
  // period.ts's own default timezone rather than this business's real configured one.
  const [me, businessSettings] = await Promise.all([
    serverApi.getMe(),
    serverApi.getBusinessSettings().catch(() => null),
  ]);
  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');
  const timeZone = businessSettings?.timezone;

  // Same shared period filter every marketing tab reads (see PeriodFilter/../period) — defaults
  // to 'all' here specifically (not the usual 'mtd'), since a funnel's drop-off shape is normally
  // read over its whole history rather than just the current month.
  const bounds = periodToBounds(parsePeriodParams(params, 'all'), timeZone);
  const [data, pages] = await Promise.all([
    serverApi.getMarketingFunnel(slug, undefined, bounds.from, bounds.to),
    serverApi.getMarketingPages(),
  ]);
  // FunnelView needs a real, non-empty slug even when `data` came back empty (this business's
  // page has recorded zero funnel steps yet) — falls back to this business's own default page
  // (pages is already scoped to the caller's own business, oldest-first, matching the backend's
  // own findDefaultSlugForBusiness). Previously this hardcoded a literal "mani" fallback, which
  // meant every button here (page switcher, Analyze, refetch) silently targeted AK.LUX.NAILS'
  // page for every other business once the backend was scoped by business_id.
  const resolvedSlug = data[0]?.landingPageSlug ?? slug ?? pages[0]?.slug ?? 'mani';

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        How far visitors get through the booking flow before dropping off — each landing page
        reports its own steps, so different flows (e.g. contact info first vs. last) are compared
        by relative position rather than assumed to be identical.
      </p>

      <div className="mt-6">
        {/* Keyed by slug so switching pages via the shared selector actually remounts this with
            fresh initialData instead of the already-mounted instance's stale state. */}
        <FunnelView key={resolvedSlug} initialData={data} slug={resolvedSlug} pages={pages} role={me.role} timeZone={timeZone} />
      </div>
    </main>
  );
}
