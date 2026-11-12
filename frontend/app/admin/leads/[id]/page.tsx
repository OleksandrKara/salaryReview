import { notFound, redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import { t } from '../../../lib/i18n';
import { enterBusinessUrl, needsSwitch, nextBusinessToTry, parseBusinessParam, parseTried } from '../../../lib/businessLinks';
import PageHeader from '../../../components/PageHeader';
import LeadCard from './LeadCard';

// A lead link may name its business (?business=<id>). If the session is on another one, switch first
// and come back, so the card opens on the first render. A link without a business (or a wrong one) falls
// back to trying the user's other businesses once each, instead of a dead "not found".
export default async function LeadPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ stop?: string; business?: string; tried?: string }> }) {
  const { id } = await params;
  const { stop, business, tried } = await searchParams;
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;
  const keep = stop === 'academy' || stop === 'consult' ? `stop=${stop}` : '';
  const wanted = parseBusinessParam(business);
  if (needsSwitch(wanted, me.activeBusinessId)) {
    redirect(enterBusinessUrl(wanted, `/admin/leads/${Number(id)}${keep ? `?${keep}` : ''}`));
  }
  const detail = await serverApi.getLead(Number(id)).catch(() => null);
  if (!detail) {
    const triedIds = [...parseTried(tried), me.activeBusinessId];
    const nextBiz = wanted === null ? nextBusinessToTry(me.businesses.map((b) => b.id), triedIds) : null;
    if (nextBiz !== null) {
      redirect(enterBusinessUrl(nextBiz, `/admin/leads/${Number(id)}?${[keep, `tried=${triedIds.join(',')}`].filter(Boolean).join('&')}`));
    }
    notFound();
  }

  if (detail.lead.id !== Number(id)) {
    redirect(`/admin/leads/${detail.lead.id}${keep ? `?${keep}` : ''}`);
  }

  return (
    <main className="mx-auto w-full min-w-0 max-w-3xl overflow-x-clip p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadsTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <LeadCard initial={detail} language={lang} canTagAcademy={me.activeBusinessId === 2} stopFunnel={stop === 'academy' || stop === 'consult' ? stop : null} />
    </main>
  );
}
