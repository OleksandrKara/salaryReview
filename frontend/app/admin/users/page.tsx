import Link from 'next/link';
import { serverApi } from '../../lib/serverApi';
import UsersManager from './UsersManager';

// Owner-only user management. The backend gates /api/users by role; the proxy also keeps providers
// out of /admin. Lists accounts and the providers available to link.
export default async function UsersPage() {
  const [users, providers] = await Promise.all([
    serverApi.listUsers(),
    serverApi.listProviders(),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-8">
      <div className="mb-6 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold">Users</h1>
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <UsersManager initialUsers={users} providers={providers} />
    </main>
  );
}
