import { notFound } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import { t } from '../../../lib/i18n';
import PageHeader from '../../../components/PageHeader';
import LeadCard from './LeadCard';

export default async function LeadPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ stop?: string }> }) {
  const { id } = await params;
  const { stop } = await searchParams;
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;
  const detail = await serverApi.getLead(Number(id)).catch(() => null);
  if (!detail) notFound();

  return (
    <main className="mx-auto max-w-3xl overflow-x-clip p-4 sm:p-8">
      <PageHeader title={t(lang, 'leadsTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <LeadCard initial={detail} language={lang} stopFunnel={stop === 'academy' || stop === 'consult' ? stop : null} />
    </main>
  );
}
