import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import RevenueTabs from '../RevenueTabs';
import ExpenseEntryForm from '../ExpenseEntryForm';

export default async function RevenueExpensesPage() {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect(me?.role === 'ADS_MANAGER' ? '/owner/marketing' : '/reports');

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Revenue" role={me.role} language={me.preferredLanguage} />
      <RevenueTabs />

      <div className="mt-6 flex flex-wrap items-center gap-2 rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-200">
        <div className="flex-1 text-xs text-zinc-500">
          Import a bank statement to auto-categorize most of a month&apos;s expenses at once,
          instead of entering each one by hand below.
        </div>
        <div className="flex flex-wrap gap-2">
          <Link
            href="/owner/overview/expenses/import"
            className="rounded bg-zinc-800 px-3 py-2 text-xs font-medium !text-white hover:bg-zinc-700"
          >
            Import statement
          </Link>
          <Link
            href="/owner/overview/expenses/history"
            className="rounded px-3 py-2 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300 hover:bg-white"
          >
            History
          </Link>
          <Link
            href="/owner/overview/expenses/rules"
            className="rounded px-3 py-2 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300 hover:bg-white"
          >
            Merchant rules
          </Link>
          <Link
            href="/owner/overview/expenses/categories"
            className="rounded px-3 py-2 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300 hover:bg-white"
          >
            Categories
          </Link>
        </div>
      </div>

      <ExpenseEntryForm />
    </main>
  );
}
