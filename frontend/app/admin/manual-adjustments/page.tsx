import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import ManualAdjustmentManager from './ManualAdjustmentManager';

// Owner: full access. Manager: read-only (recording/removing an adjustment is an owner-only payroll
// decision; managers just need visibility into what's been adjusted) — a deliberate exception for
// money Square can't reflect on its own: a credit for a service recorded too messily to
// auto-attribute (e.g. a card-machine payment with no service line, or the wrong date), or a
// deduction for something like a refunded service.
export default async function ManualAdjustmentsPage() {
  const [me, adjustments, providers] = await Promise.all([
    serverApi.getMe(),
    serverApi.listManualAdjustments(),
    serverApi.listProviders(),
  ]);
  const canEdit = me.role === 'OWNER';

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Manual adjustments" />
      <p className="mb-6 text-xs text-zinc-500">
        Credit a provider for a service Square couldn&apos;t auto-attribute (paid on a card machine with
        no service line, checked out under the wrong date, etc.), or deduct a provider&apos;s commission for
        something like a refunded service. A credit&apos;s <span className="font-medium">gross</span> is the
        commission basis, with any salon-absorbed <span className="font-medium">discount</span> and{' '}
        <span className="font-medium">tip</span>; a deduction has no discount or tip and requires a reason.
        Both pay out (or claw back) exactly like a card service (shown as an{' '}
        <span className="font-medium text-sky-700">ADJUSTMENT</span> line) — neither ever touches Square or
        charges the customer.
      </p>
      <ManualAdjustmentManager initialAdjustments={adjustments} providers={providers.filter((p) => p.active)} canEdit={canEdit} />
    </main>
  );
}
