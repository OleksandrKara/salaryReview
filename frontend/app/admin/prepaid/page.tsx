import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import SetupRequiredNotice from '../../components/SetupRequiredNotice';
import PrepaidManager from './PrepaidManager';

// Owner/manager prepaid packages. The backend gates /api/prepaid by role; the proxy keeps providers
// out of /admin. A package belongs to a customer; any provider's visit can draw it down.
export default async function PrepaidPage() {
  const squareConnection = await serverApi.getSquareConnection();
  if (!squareConnection.accessTokenSet) {
    return (
      <main className="mx-auto max-w-4xl p-4 sm:p-8">
        <PageHeader title="Prepaid packages" />
        <SetupRequiredNotice
          title="Connect Square to manage prepaid packages"
          message="Prepaid packages are matched against real Square bookings and payments, which needs a Square connection first."
          ctaHref="/owner/settings/square"
          ctaLabel="Connect Square"
        />
      </main>
    );
  }

  const packages = await serverApi.listPrepaid();

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Prepaid packages" />
      <p className="mb-6 text-xs text-zinc-500">
        A customer who paid one Square invoice in advance for several services — drawn down across any
        provider they visit. Draw-downs are confirmed against real bookings and pay the provider who
        performed each one on the service date; the balance prevents over-redemption.
      </p>
      <PrepaidManager initialPackages={packages} />
    </main>
  );
}
