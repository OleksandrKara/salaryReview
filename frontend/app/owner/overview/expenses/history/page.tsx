import { redirect } from 'next/navigation';
import { serverApi } from '../../../../lib/serverApi';
import PageHeader from '../../../../components/PageHeader';
import ImportHistoryList from './ImportHistoryList';

export default async function ImportHistoryPage() {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Import history" role={me.role} language={me.preferredLanguage} />
      <ImportHistoryList />
    </main>
  );
}
