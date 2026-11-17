import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { enterBusinessUrl, needsSwitch, parseBusinessParam } from '../../lib/businessLinks';
import PageHeader from '../../components/PageHeader';
import LeadsManager from './LeadsManager';
import { filtersToApiParams, isDefault, parseFilters } from './leadFilters';
import ShiftSummary from './ShiftSummary';

export default async function LeadsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const one = (k: string) => (Array.isArray(sp[k]) ? sp[k][0] : sp[k]) as string | undefined;
  const business = one('business');
  const tab = one('tab');
  const me = await serverApi.getMe();
  const wanted = parseBusinessParam(business);
  if (needsSwitch(wanted, me.activeBusinessId)) redirect(enterBusinessUrl(wanted, tab === 'needs-reply' ? '/admin/leads?tab=needs-reply' : '/admin/leads'));
  const lang = me.preferredLanguage;
  const perms = me.permissions ?? [];
  const staff = me.role === 'OWNER' || me.role === 'MANAGER';
  if (!staff && !perms.includes('LEADS_ALL') && !perms.includes('LEADS_ACADEMY')) redirect('/me');
  const academyOnly = !staff && !perms.includes('LEADS_ALL');
  const query = new URLSearchParams();
  Object.entries(sp).forEach(([k, v]) => (Array.isArray(v) ? v : v == null ? [] : [v]).forEach((x) => query.append(k, x)));
  const filters = parseFilters(query);
  const page = isDefault(filters) ? null : await serverApi.searchLeads(filtersToApiParams(filters).toString());
  const leads = page ? page.items.map((i) => ({ ...i.lead, lastActivityAt: i.lastActivityAt })) : await serverApi.listLeads();

  return (
    <main className="mx-auto w-full min-w-0 max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadsTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      {me.role === 'MANAGER' ? <ShiftSummary language={lang} /> : null}
      <p className="mb-6 text-xs text-zinc-500">{t(lang, 'leadsDesc')}</p>
      {academyOnly ? null : <Link href="/admin/leads/lost-report" className="mb-4 inline-block text-sm text-zinc-600 underline">{t(lang, 'leadLostReport')}</Link>}
      <LeadsManager initialLeads={leads} language={lang} restricted={academyOnly} initialTab={tab ?? null} initialFilters={filters} initialTotal={page ? page.total : null} />
    </main>
  );
}
