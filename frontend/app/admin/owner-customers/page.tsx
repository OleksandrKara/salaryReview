import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import OwnerCustomerManager from './OwnerCustomerManager';

// Owner/manager: the Square customers who are owner(s)/family. A booking for one of them with no
// Square order (they aren't charged) still credits the provider their commission at the menu price.
// The backend gates /api/owner-customers by role; the proxy keeps providers out of /admin.
export default async function OwnerCustomersPage() {
  const customers = await serverApi.listOwnerCustomers();

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Owner customers" />
      <p className="mb-6 text-xs text-zinc-500">
        When one of these customers receives a service, the owner isn&apos;t charged — so Square has no
        payment for it. The provider who did the work is still credited their commission on the
        service&apos;s menu price (shown as a <span className="font-medium text-rose-700">COMP</span> line
        in the reports). Only past bookings with no Square order are credited.
      </p>
      <OwnerCustomerManager initialCustomers={customers} />
    </main>
  );
}
