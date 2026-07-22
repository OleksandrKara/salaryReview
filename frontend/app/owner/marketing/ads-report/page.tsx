import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import AdsReportView from './AdsReportView';
import MarketingTabs, { DEFAULT_SLUG } from '../MarketingTabs';

export default async function MarketingAdsReportPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string }>;
}) {
  const { slug: rawSlug } = await searchParams;
  // The shared page selector (MarketingTabs) always shows DEFAULT_SLUG pre-selected when ?slug=
  // is absent — this has to actually fetch that page's data rather than silently pooling every
  // page together, both so the numbers match what the selector claims is showing, and because
  // pooling is the slowest possible case (every page's ads customers combined into one live
  // Square sweep) for what a first-time visitor sees by default.
  const slug = rawSlug ?? DEFAULT_SLUG;
  const [me, data] = await Promise.all([
    serverApi.getMe(),
    serverApi.getMarketingAdsReport('mtd', undefined, undefined, undefined, slug),
  ]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Ads Report" role={me.role} language={me.preferredLanguage} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        Ad spend, ROI, and volume side by side by week or month — defaults to Meta &amp; Google ad
        clicks, with the option below to include organic/direct traffic too.
      </p>

      <div className="mt-6">
        {/* Keyed by slug so switching pages via the shared selector — a client-side navigation
            that wouldn't otherwise remount this component — actually re-fetches for the new page
            instead of keeping whatever it last showed. */}
        <AdsReportView key={slug} initialData={data} slug={slug} />
      </div>
    </main>
  );
}
