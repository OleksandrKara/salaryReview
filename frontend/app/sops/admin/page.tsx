import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import SopAdmin from './SopAdmin';

// Owner SOP management. Non-owners are bounced to the staff view (the API also enforces this).
export default async function SopAdminPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect('/sops');
  const sops = await serverApi.listSops();

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <div className="mb-6 flex items-center justify-between">
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <h1 className="text-lg font-semibold">SOPs — admin</h1>
      <p className="mt-1 text-sm text-zinc-500">
        Author policy documents, publish versions, target an audience, and see who has acknowledged.
      </p>
      <SopAdmin initialSops={sops} />
    </main>
  );
}
