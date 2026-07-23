import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
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
  // Same shared period filter every marketing tab reads (see PeriodFilter/./period) — defaults to
  // Month to date, layered on top of (never replacing) this page's own permanent "Hide stats
  // before" cutoff, which the backend intersects with these bounds itself.
  const bounds = periodToBounds(parsePeriodParams(params));
  const [me, data, abuseBlocks] = await Promise.all([
    serverApi.getMe(),
    serverApi.getMarketingDashboard(slug, undefined, bounds.from, bounds.to),
    serverApi.getAbuseBlocks(),
  ]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing" role={me.role} language={me.preferredLanguage} />
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
