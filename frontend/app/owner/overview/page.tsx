import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import { SyncBadge } from '../../components/SyncBadge';
import OverviewClient from './OverviewClient';
import ProviderTable from './ProviderTable';
import RevenueTabs from './RevenueTabs';

export default async function OwnerOverviewPage({
  searchParams,
}: {
  searchParams: Promise<{ fromYear?: string; fromMonth?: string; toYear?: string; toMonth?: string }>;
}) {
  const sp = await searchParams;
  const now = new Date();
  const curYear  = now.getUTCFullYear();
  const curMonth = now.getUTCMonth() + 1;

  // Default: last 12 complete months (exclude the current unfinished month).
  const defToMonth = curMonth === 1 ? 12 : curMonth - 1;
  const defToYear  = curMonth === 1 ? curYear - 1 : curYear;
  const defFromDate = new Date(defToYear, defToMonth - 1 - 11);
  const defFromYear  = defFromDate.getFullYear();
  const defFromMonth = defFromDate.getMonth() + 1;

  const fromYear  = Number(sp.fromYear)  || defFromYear;
  const fromMonth = Number(sp.fromMonth) || defFromMonth;
  const toYear    = Number(sp.toYear)    || defToYear;
  const toMonth   = Number(sp.toMonth)   || defToMonth;

  const [me, data] = await Promise.all([
    serverApi.getMe(),
    serverApi.getOwnerOverview(fromYear, fromMonth, toYear, toMonth),
  ]);

  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Revenue" role={me.role} language={me.preferredLanguage} />
      <div className="mb-3">
        <SyncBadge syncedAt={data.syncedAt} language={me.preferredLanguage} />
      </div>
      <RevenueTabs />

      <OverviewClient data={data} />

      {data.providers.length > 0 && (
        <div className="mt-6">
          <h2 className="mb-2 text-sm font-medium text-zinc-500">
            Provider revenue — settled months only
          </h2>
          <ProviderTable providers={data.providers} />
        </div>
      )}
    </main>
  );
}
