import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import AnalyticsView from './AnalyticsView';
import MarketingTabs from '../MarketingTabs';

export default async function MarketingAnalyticsPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string }>;
}) {
  const { slug } = await searchParams;
  const [me, data] = await Promise.all([serverApi.getMe(), serverApi.getMarketingAnalytics(undefined, undefined, undefined, slug)]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Marketing Analytics" role={me.role} language={me.preferredLanguage} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        Customers, services, and gross revenue — defaults to Meta &amp; Google ad clicks (everyone who first,
        or most recently, came in through a paid ad), with the option below to include organic/direct traffic too.
      </p>

      <div className="mt-6">
        {/* Keyed by slug so switching pages via the shared selector (a client-side navigation that
            doesn't otherwise remount this component) forces a fresh mount — otherwise the already-
            mounted instance keeps showing whatever it last fetched, ignoring the new initialData. */}
        <AnalyticsView key={slug ?? 'default'} initialData={data} slug={slug} />
      </div>
    </main>
  );
}
