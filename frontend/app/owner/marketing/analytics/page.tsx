import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import AnalyticsView from './AnalyticsView';

export default async function MarketingAnalyticsPage() {
  const [me, data] = await Promise.all([serverApi.getMe(), serverApi.getMarketingAnalytics()]);

  if (me?.role !== 'OWNER') redirect('/reports');

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Marketing Analytics" role={me.role} language={me.preferredLanguage} />

      <Link href="/owner/marketing" className="mt-2 inline-block text-sm font-medium text-blue-600 hover:underline">
        ← Back to Marketing
      </Link>

      <p className="mt-4 text-sm text-zinc-500">
        Customers, services, and gross revenue attributed to Meta &amp; Google ad clicks — i.e. everyone who
        first (or most recently) came in through a paid ad, not organic or direct traffic.
      </p>

      <div className="mt-6">
        <AnalyticsView initialData={data} />
      </div>
    </main>
  );
}
