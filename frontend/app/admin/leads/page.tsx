import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { enterBusinessUrl, needsSwitch, parseBusinessParam } from '../../lib/businessLinks';
import PageHeader from '../../components/PageHeader';
import LeadsManager from './LeadsManager';
import ShiftSummary from './ShiftSummary';

export default async function LeadsPage({ searchParams }: { searchParams: Promise<{ business?: string }> }) {
  const { business } = await searchParams;
  const me = await serverApi.getMe();
  const wanted = parseBusinessParam(business);
  if (needsSwitch(wanted, me.activeBusinessId)) redirect(enterBusinessUrl(wanted, '/admin/leads'));
  const lang = me.preferredLanguage;
  const perms = me.permissions ?? [];
  const staff = me.role === 'OWNER' || me.role === 'MANAGER';
  if (!staff && !perms.includes('LEADS_ALL') && !perms.includes('LEADS_ACADEMY')) redirect('/me');
  const academyOnly = !staff && !perms.includes('LEADS_ALL');
  const leads = await serverApi.listLeads();

  return (
    <main className="mx-auto w-full min-w-0 max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadsTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      {me.role === 'MANAGER' ? <ShiftSummary language={lang} /> : null}
      <p className="mb-6 text-xs text-zinc-500">{t(lang, 'leadsDesc')}</p>
      {academyOnly ? null : <Link href="/admin/leads/lost-report" className="mb-4 inline-block text-sm text-zinc-600 underline">{t(lang, 'leadLostReport')}</Link>}
      <LeadsManager initialLeads={leads} language={lang} restricted={academyOnly} />
    </main>
  );
}
