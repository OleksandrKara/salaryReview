import Link from 'next/link';
import { serverApi } from '../../lib/serverApi';
import PrepaidManager from './PrepaidManager';

// Owner/manager prepaid packages. The backend gates /api/prepaid by role; the proxy keeps providers
// out of /admin. A package belongs to a customer; any provider's visit can draw it down.
export default async function PrepaidPage() {
  const packages = await serverApi.listPrepaid();

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <div className="mb-1 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold">Prepaid packages</h1>
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        A customer who paid one Square invoice in advance for several services — drawn down across any
        provider they visit. Draw-downs are confirmed against real bookings and pay the provider who
        performed each one on the service date; the balance prevents over-redemption.
      </p>
      <PrepaidManager initialPackages={packages} />
    </main>
  );
}
