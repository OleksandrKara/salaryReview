import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import AdsReportView from './AdsReportView';
import MarketingTabs from '../MarketingTabs';
import { parsePeriodParams } from '../period';

export default async function MarketingAdsReportPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string; period?: string; from?: string; to?: string }>;
}) {
  const params = await searchParams;
  // slug is passed through as-is (possibly undefined) — the backend resolves its own
  // business-scoped default landing page when omitted (see MarketingDashboardRepository#
  // findDefaultSlugForBusiness). Previously this hardcoded a literal "mani" fallback, which
  // meant any business other than AK.LUX.NAILS always got an explicit request for AK.LUX.NAILS'
  // own page — empty (not leaked, just wrong) after the backend was scoped by business_id.
  const { slug } = params;
  // Same shared period filter every marketing tab reads (see PeriodFilter/../period) — defaults
  // to Month to date when absent, matching this endpoint's own pre-existing default so a bare
  // /owner/marketing/ads-report link behaves exactly as it always has.
  const selection = parsePeriodParams(params);
  const [me, data] = await Promise.all([
    serverApi.getMe(),
    serverApi.getMarketingAdsReport(selection.period, selection.from, selection.to, undefined, slug),
  ]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Ads Report" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        Ad spend, ROI, and volume side by side by week or month — defaults to Meta &amp; Google ad
        clicks, with the option below to include organic/direct traffic too.
      </p>

      <div className="mt-6">
        {/* Keyed by slug so switching pages via the shared selector — a client-side navigation
            that wouldn't otherwise remount this component — actually re-fetches for the new page
            instead of keeping whatever it last showed. */}
        <AdsReportView key={slug} initialData={data} slug={slug} language={me.preferredLanguage} />
      </div>
    </main>
  );
}
