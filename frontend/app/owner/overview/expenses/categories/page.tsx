import { redirect } from 'next/navigation';
import { serverApi } from '../../../../lib/serverApi';
import PageHeader from '../../../../components/PageHeader';
import ExpenseCategoriesTable from './ExpenseCategoriesTable';

export default async function ExpenseCategoriesPage() {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Expense categories" role={me.role} language={me.preferredLanguage} />
      <p className="mt-2 text-sm text-zinc-500">
        Add, rename, or remove the categories offered when entering an expense or reconciling a
        bank statement. Manager time and provider payroll are built in and can&apos;t be removed.
      </p>
      <ExpenseCategoriesTable />
    </main>
  );
}
