import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import PermissionsManager from './PermissionsManager';

// Owner-only. Grants optional per-user permissions (today: SMS inbox) for the active business.
export default async function PermissionsPage() {
  const overview = await serverApi.getPermissionsOverview();
  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title="Permissions" />
      <PermissionsManager initial={overview} />
    </main>
  );
}
