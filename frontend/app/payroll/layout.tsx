import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import PayrollTabs from './PayrollTabs';

export default async function PayrollLayout({ children }: { children: React.ReactNode }) {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto max-w-5xl p-3 sm:p-8">
      <PageHeader title={t(lang, 'tcPayroll')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <PayrollTabs lang={lang ?? 'EN'} />
      {children}
    </main>
  );
}
