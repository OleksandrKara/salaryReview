import Link from 'next/link';
import { serverApi } from '../../lib/serverApi';
import type { SquareRosterEntry } from '../../lib/types';
import UsersManager from './UsersManager';

// Owner-only user management. The backend gates /api/users by role; the proxy also keeps providers
// out of /admin. Lists accounts, the providers available to link, and the Square team roster (for the
// "import from Square" flow — tolerates Square being unreachable).
export default async function UsersPage() {
  const [users, providers, roster] = await Promise.all([
    serverApi.listUsers(),
    serverApi.listProviders(),
    serverApi.getSquareRoster().catch(() => [] as SquareRosterEntry[]),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-8">
      <div className="mb-6 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold">Users</h1>
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <UsersManager initialUsers={users} providers={providers} roster={roster} />
    </main>
  );
}
