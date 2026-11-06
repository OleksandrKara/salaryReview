import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import PageHeader from '../../components/PageHeader';
import LeadsManager from './LeadsManager';

export default async function LeadsPage() {
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;
  const perms = me.permissions ?? [];
  const staff = me.role === 'OWNER' || me.role === 'MANAGER';
  if (!staff && !perms.includes('LEADS_ALL') && !perms.includes('LEADS_ACADEMY')) redirect('/me');
  const academyOnly = !staff && !perms.includes('LEADS_ALL');
  const leads = await serverApi.listLeads();

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadsTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <p className="mb-6 text-xs text-zinc-500">{t(lang, 'leadsDesc')}</p>
      {academyOnly ? null : <Link href="/admin/leads/lost-report" className="mb-4 inline-block text-sm text-zinc-600 underline">{t(lang, 'leadLostReport')}</Link>}
      <LeadsManager initialLeads={leads} language={lang} />
    </main>
  );
}
