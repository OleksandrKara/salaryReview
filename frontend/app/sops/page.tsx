import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import SopList from './SopList';

// Standard Operating Procedures — the shared read + acknowledge view. The API returns only the SOPs
// this role may see (audience-filtered, published, active), so the page renders what it's given.
export default async function SopsPage() {
  const [me, sops] = await Promise.all([serverApi.getMe(), serverApi.listSops()]);
  const backHref = me.role === 'PROVIDER' ? '/me' : '/reports';

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <div className="mb-6 flex items-center justify-between">
        <Link href={backHref} className="text-xs text-zinc-400 hover:text-zinc-600">← Back</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <h1 className="text-lg font-semibold">Standard operating procedures</h1>
      {me.role === 'OWNER' ? (
        <p className="mt-1 text-sm text-zinc-500">
          You author SOPs on the <Link href="/sops/admin" className="underline">admin page</Link>. Below is
          everything as staff see it.
        </p>
      ) : (
        <p className="mt-1 text-sm text-zinc-500">Open each SOP and acknowledge that you have read and agree to follow it.</p>
      )}
      <SopList role={me.role} initialSops={sops} />
    </main>
  );
}
