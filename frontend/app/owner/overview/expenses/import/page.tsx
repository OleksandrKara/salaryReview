import { redirect } from 'next/navigation';
import { serverApi } from '../../../../lib/serverApi';
import PageHeader from '../../../../components/PageHeader';
import StatementUploadForm from './StatementUploadForm';

export default async function ImportStatementPage() {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  return (
    <main className="mx-auto max-w-xl p-4 sm:p-8">
      <PageHeader title="Import statement" role={me.role} language={me.preferredLanguage} />
      <p className="mt-2 text-sm text-zinc-500">
        Upload this month&apos;s bank statement CSV. Most transactions are categorized
        automatically — you&apos;ll only need to review the handful the system isn&apos;t sure
        about.
      </p>
      <StatementUploadForm />
    </main>
  );
}
