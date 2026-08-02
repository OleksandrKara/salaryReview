import { redirect } from 'next/navigation';
import { serverApi } from '../../../../lib/serverApi';
import PageHeader from '../../../../components/PageHeader';
import MerchantRulesTable from './MerchantRulesTable';

export default async function MerchantRulesPage() {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Merchant rules" role={me.role} language={me.preferredLanguage} />
      <p className="mt-2 text-sm text-zinc-500">
        Every rule learned from a reconciliation decision — view, correct, or remove any of them
        directly, not only reactively through a transaction correction.
      </p>
      <MerchantRulesTable />
    </main>
  );
}
