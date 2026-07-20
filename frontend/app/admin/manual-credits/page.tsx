import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import ManualCreditManager from './ManualCreditManager';

// Owner: full access. Manager: read-only (recording/removing a credit is an owner-only payroll
// decision; managers just need visibility into what's been credited) — a deliberate exception for
// a service Square recorded too messily to auto-attribute (e.g. a card-machine payment with no
// service line, or the wrong date).
export default async function ManualCreditsPage() {
  const [me, credits, providers] = await Promise.all([
    serverApi.getMe(),
    serverApi.listManualCredits(),
    serverApi.listProviders(),
  ]);
  const canEdit = me.role === 'OWNER';

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Manual credits" />
      <p className="mb-6 text-xs text-zinc-500">
        Credit a provider for a service Square couldn&apos;t auto-attribute (paid on a card machine with
        no service line, checked out under the wrong date, etc.). Enter the service&apos;s{' '}
        <span className="font-medium">gross</span> (commission basis), any salon-absorbed{' '}
        <span className="font-medium">discount</span>, and the <span className="font-medium">tip</span>. It
        pays out exactly like a card service (shown as a <span className="font-medium text-sky-700">MANUAL</span>{' '}
        line) — it never touches Square or charges the customer.
      </p>
      <ManualCreditManager initialCredits={credits} providers={providers.filter((p) => p.active)} canEdit={canEdit} />
    </main>
  );
}
