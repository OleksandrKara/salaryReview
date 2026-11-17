import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import PayrollBreakdown from '../timecards/PayrollBreakdown';

export default async function PayrollPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto max-w-5xl p-3 sm:p-8">
      <PageHeader title={t(lang, 'pbTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <PayrollBreakdown lang={lang ?? 'EN'} owner={me.role === 'OWNER'} />
    </main>
  );
}
