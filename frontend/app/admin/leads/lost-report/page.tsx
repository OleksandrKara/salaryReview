import Link from 'next/link';
import { serverApi } from '../../../lib/serverApi';
import { t } from '../../../lib/i18n';
import PageHeader from '../../../components/PageHeader';
import { parseLostFilters } from '../../../lib/lostReport';
import LostReportView from './LostReportView';

export default async function LostReportPage({
  searchParams,
}: { searchParams: Promise<{ days?: string; from?: string; to?: string; source?: string | string[]; reason?: string }> }) {
  const f = parseLostFilters(await searchParams);
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;
  const report = await serverApi.getLostReport(f.days, f.from, f.to);

  return (
    <main className="mx-auto w-full min-w-0 max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadLostReportTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <Link href="/admin/leads" className="text-sm text-zinc-600 underline">{t(lang, 'leadsBack')}</Link>
      <LostReportView f={f} report={report} lang={lang} basePath="/admin/leads/lost-report" leadHref={(id) => `/admin/leads/${id}`} />
    </main>
  );
}
