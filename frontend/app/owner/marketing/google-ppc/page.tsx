import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import { cookieToParams, parsePeriodParams, periodToBounds, PPC_PERIOD_COOKIE } from '../period';
import GooglePpcView from './GooglePpcView';

export default async function GooglePpcPage({
  searchParams,
}: {
  searchParams: Promise<{ period?: string; from?: string; to?: string }>;
}) {
  const urlParams = await searchParams;
  // An explicit ?period= in the link wins; otherwise the period chosen last time on this tab is restored.
  const params = urlParams.period ? urlParams : (cookieToParams((await cookies()).get(PPC_PERIOD_COOKIE)?.value) ?? urlParams);
  const [me, businessSettings] = await Promise.all([
    serverApi.getMe(),
    serverApi.getBusinessSettings().catch(() => null),
  ]);
  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');
  const timeZone = businessSettings?.timezone;
  const selection = parsePeriodParams(params);
  const bounds = periodToBounds(selection, timeZone);
  const [data, leads, money, margins] = await Promise.all([
    serverApi.getGooglePpc(bounds.from, bounds.to).catch(() => null),
    serverApi.getGooglePpcLeads(bounds.from, bounds.to).catch(() => []),
    serverApi.getGooglePpcMoney(bounds.from, bounds.to).catch(() => null),
    serverApi.getMargins(),
  ]);

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title="Google PPC" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />
      <GooglePpcView initialSelection={selection} initialData={data} initialLeads={leads} initialMoney={money} initialMargins={margins} timeZone={timeZone} />
    </main>
  );
}
