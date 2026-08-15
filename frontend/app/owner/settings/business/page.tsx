import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import BusinessSettingsForm from './BusinessSettingsForm';

// Owner-only. This business's own name/timezone and financial config (base commission rate, the
// tier bonus program on/off, card tip fee rate) — everything SettlementPreviewService/
// TierCommissionEngine actually read at payout time.
export default async function BusinessSettingsPage() {
  const settings = await serverApi.getBusinessSettings();

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="Business settings" />
      <p className="mt-1 text-sm text-zinc-500">
        Name, timezone, and the financial config that drives every payout for this business.
      </p>
      <BusinessSettingsForm initialSettings={settings} />
    </main>
  );
}
