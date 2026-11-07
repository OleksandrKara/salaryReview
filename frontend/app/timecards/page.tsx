import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import TimecardsApproval from './TimecardsApproval';

export default async function TimecardsPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'tcNavApprove')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <TimecardsApproval role={me.role} language={lang ?? 'EN'} />
    </main>
  );
}
