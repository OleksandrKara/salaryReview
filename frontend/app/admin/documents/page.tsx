import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import StaffDocumentsManager from './StaffDocumentsManager';

// Owner-only: per-person documents (contracts, licenses, NDAs, etc.) for service providers and
// managers, each with a required expiration date. The backend gates /api/owner/staff-documents by
// role; the proxy keeps providers/managers out of /admin.
export default async function StaffDocumentsPage() {
  const [me, documents, providers, users] = await Promise.all([
    serverApi.getMe(),
    serverApi.listStaffDocuments(),
    serverApi.listProviders(),
    serverApi.listUsers(),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Staff Documents" role={me.role} language={me.preferredLanguage} />
      <p className="mb-6 text-xs text-zinc-500">
        Contracts, licenses, NDAs, and other per-person documents for service providers and
        managers — each with an expiration date so nothing lapses unnoticed.
      </p>
      <StaffDocumentsManager
        initialDocuments={documents}
        providers={providers.filter((p) => p.active)}
        managers={users.filter((u) => u.role === 'MANAGER' && u.active)}
      />
    </main>
  );
}
