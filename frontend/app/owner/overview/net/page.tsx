import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import SetupRequiredNotice from '../../../components/SetupRequiredNotice';
import RevenueTabs from '../RevenueTabs';
import RangePicker from '../RangePicker';
import NetSummary from '../NetSummary';
import NetTable from '../NetTable';

export default async function RevenueNetPage({
  searchParams,
}: {
  searchParams: Promise<{ fromYear?: string; fromMonth?: string; toYear?: string; toMonth?: string }>;
}) {
  const sp = await searchParams;
  const now = new Date();
  const curYear  = now.getUTCFullYear();
  const curMonth = now.getUTCMonth() + 1;

  // Same default range as the Gross tab (last 12 complete months) — kept in sync so switching
  // tabs without touching the range picker shows the same months on both.
  const defToMonth = curMonth === 1 ? 12 : curMonth - 1;
  const defToYear  = curMonth === 1 ? curYear - 1 : curYear;
  const defFromDate = new Date(defToYear, defToMonth - 1 - 11);
  const defFromYear  = defFromDate.getFullYear();
  const defFromMonth = defFromDate.getMonth() + 1;

  const fromYear  = Number(sp.fromYear)  || defFromYear;
  const fromMonth = Number(sp.fromMonth) || defFromMonth;
  const toYear    = Number(sp.toYear)    || defToYear;
  const toMonth   = Number(sp.toMonth)   || defToMonth;

  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  const squareConnection = await serverApi.getSquareConnection();
  if (!squareConnection.accessTokenSet) {
    return (
      <main className="mx-auto max-w-6xl p-4 sm:p-8">
        <PageHeader title="Revenue" role={me.role} language={me.preferredLanguage} />
        <SetupRequiredNotice
          title="Connect Square to see revenue"
          message="This business's revenue dashboard needs a Square connection to pull bookings, orders, and payments from."
          ctaHref="/owner/settings/square"
          ctaLabel="Connect Square"
        />
      </main>
    );
  }

  const [data, categories] = await Promise.all([
    serverApi.getOwnerOverview(fromYear, fromMonth, toYear, toMonth),
    serverApi.listExpenseCategories(),
  ]);

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Revenue" role={me.role} language={me.preferredLanguage} />
      <RevenueTabs />

      <div className="mb-4" data-testid="net-range-picker">
        <RangePicker
          fromYear={data.fromYear}
          fromMonth={data.fromMonth}
          toYear={data.toYear}
          toMonth={data.toMonth}
          basePath="/owner/overview/net"
        />
      </div>

      <div className="mb-4" data-testid="net-summary">
        <NetSummary data={data} categories={categories} />
      </div>

      <NetTable months={data.months} categories={categories} />
    </main>
  );
}
