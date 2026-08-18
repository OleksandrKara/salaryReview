import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import SetupRequiredNotice from '../../components/SetupRequiredNotice';
import AbuseBlocksPanel from './AbuseBlocksPanel';
import MarketingManager from './MarketingManager';
import MarketingTabs from './MarketingTabs';
import { parsePeriodParams, periodToBounds } from './period';

export default async function MarketingDashboardPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string; period?: string; from?: string; to?: string }>;
}) {
  const params = await searchParams;
  const { slug } = params;
  // getBusinessSettings is OWNER-only (403 for ADS_MANAGER) — same fails-open reasoning as
  // getSquareConnection below: an ADS_MANAGER still gets the dashboard, just falling back to
  // period.ts's own default timezone rather than this business's real configured one.
  const [me, businessSettings] = await Promise.all([
    serverApi.getMe(),
    serverApi.getBusinessSettings().catch(() => null),
  ]);
  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');
  const timeZone = businessSettings?.timezone;

  // Same shared period filter every marketing tab reads (see PeriodFilter/./period) — defaults to
  // 'all' here (like Funnel, unlike Overview's usual 'mtd'), layered on top of (never replacing)
  // this page's own permanent "Hide stats before" cutoff, which the backend intersects with these
  // bounds itself.
  const bounds = periodToBounds(parsePeriodParams(params, 'all'), timeZone);

  // ADS_MANAGER can't reach the Square settings page (OWNER-only) — the request 403s and this
  // fails open, same reasoning as /admin/redos, letting an ADS_MANAGER through to the normal
  // (possibly empty) dashboard rather than blocking on a check they have no way to act on.
  const squareConnection = await serverApi.getSquareConnection().catch(() => null);
  if (squareConnection && !squareConnection.accessTokenSet) {
    return (
      <main className="mx-auto max-w-6xl p-4 sm:p-8">
        <PageHeader title="Marketing" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
        <SetupRequiredNotice
          title="Connect Square to see marketing data"
          message="Marketing tracking is matched against real Square bookings and customers, which needs a Square connection first."
          ctaHref={me.role === 'OWNER' ? '/owner/settings/square' : undefined}
          ctaLabel={me.role === 'OWNER' ? 'Connect Square' : undefined}
        />
      </main>
    );
  }

  const [data, abuseBlocks] = await Promise.all([
    serverApi.getMarketingDashboard(slug, undefined, bounds.from, bounds.to),
    serverApi.getAbuseBlocks(),
  ]);

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />

      {!data.available ? (
        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          Marketing tracking isn&apos;t available yet — no experiment data has been recorded for
          this landing page.
        </div>
      ) : (
        <>
          <div className="mt-4 flex items-center gap-2">
            <span className="text-sm text-zinc-500">Landing page: {data.landingPageSlug}</span>
          </div>

          <div className="mt-6">
            <h2 className="mb-2 text-sm font-medium text-zinc-500">Variant performance</h2>
            {/* Keyed by slug so switching pages via the shared selector (a client-side navigation
                that doesn't otherwise remount this component) forces a fresh mount — otherwise the
                already-mounted instance keeps showing whatever it last fetched, ignoring the new
                initialVariants. Same fix already applied to AdsReportView and FunnelView. */}
            <MarketingManager
              key={data.landingPageSlug}
              slug={data.landingPageSlug}
              initialVariants={data.variants}
              initialStatsSince={data.statsSince}
              readOnly={me.role !== 'OWNER'}
              timeZone={timeZone}
            />
          </div>

          {/* Blocked booking attempts are only meaningful for pages with their own booking form
              (abuse_blocks isn't scoped per landing page in the backend yet, and a page like the
              akluxnails.com homepage has no booking form of its own — "Book Now" links out to
              mani's, so showing this here would misleadingly look like attempts on this page). */}
          {data.landingPageSlug === 'mani' && <AbuseBlocksPanel data={abuseBlocks} />}
        </>
      )}
    </main>
  );
}
