import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import PayrollDays from './PayrollDays';

export default async function PayrollPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto w-full min-w-0 max-w-3xl p-3 sm:p-6 lg:max-w-4xl lg:p-8">
      <PageHeader title={t(lang, 'tcPayroll')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <PayrollDays lang={lang ?? 'EN'} owner={me.role === 'OWNER'} />
    </main>
  );
}
