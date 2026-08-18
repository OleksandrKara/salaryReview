import { redirect } from 'next/navigation';
import { serverApi } from '../../../../../lib/serverApi';
import PageHeader from '../../../../../components/PageHeader';
import ReconciliationWorkspace from './ReconciliationWorkspace';

export default async function ReconciliationPage({ params }: { params: Promise<{ importId: string }> }) {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  const { importId } = await params;

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Reconcile statement" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <ReconciliationWorkspace importId={Number(importId)} />
    </main>
  );
}
