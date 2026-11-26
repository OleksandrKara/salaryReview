import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import { t } from '../../../lib/i18n';
import PageHeader from '../../../components/PageHeader';
import { parseLostFilters } from '../../../lib/lostReport';
import LostReportView from '../../../admin/leads/lost-report/LostReportView';
import MarketingTabs from '../MarketingTabs';

export default async function MarketingLostReportPage({
  searchParams,
}: { searchParams: Promise<{ days?: string; from?: string; to?: string; source?: string | string[]; reason?: string; campaign?: string | string[] }> }) {
  const f = parseLostFilters(await searchParams);
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');
  const lang = me.preferredLanguage;
  const [report, campaigns] = await Promise.all([
    serverApi.getAdLostReport(f.days, f.from, f.to, f.campaigns),
    serverApi.getLostReportCampaigns().catch(() => []),
  ]);

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadLostReportTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />
      <LostReportView f={f} report={report} lang={lang} basePath="/owner/marketing/lost-report" campaigns={campaigns} leadHref={(id) => `/owner/marketing/leads/${id}`} />
    </main>
  );
}
