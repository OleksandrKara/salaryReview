import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import { parsePeriodParams, periodToBounds } from '../period';
import GooglePpcView from './GooglePpcView';

export default async function GooglePpcPage({
  searchParams,
}: {
  searchParams: Promise<{ period?: string; from?: string; to?: string }>;
}) {
  const params = await searchParams;
  const [me, businessSettings] = await Promise.all([
    serverApi.getMe(),
    serverApi.getBusinessSettings().catch(() => null),
  ]);
  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');
  const timeZone = businessSettings?.timezone;
  const bounds = periodToBounds(parsePeriodParams(params), timeZone);
  const data = await serverApi.getGooglePpc(bounds.from, bounds.to).catch(() => null);

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title="Google PPC" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />
      <GooglePpcView initialData={data} timeZone={timeZone} />
    </main>
  );
}
