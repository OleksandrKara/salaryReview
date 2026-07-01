import { serverApi } from '../../lib/serverApi';
import type { SquareRosterEntry } from '../../lib/types';
import PageHeader from '../../components/PageHeader';
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
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Users" />
      <UsersManager initialUsers={users} providers={providers} roster={roster} />
    </main>
  );
}
