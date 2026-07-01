import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import RedoManager from './RedoManager';

// Owner/manager: redos. Recording a redo moves a service's commission from the original provider to
// the provider who redid it. The backend gates /api/redos by role; the proxy keeps providers out of
// /admin.
export default async function RedosPage() {
  const [me, redos, providers] = await Promise.all([
    serverApi.getMe(),
    serverApi.listRedos(),
    serverApi.listProviders(),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Redos" role={me.role} language={me.preferredLanguage} />
      <p className="mb-6 text-xs text-zinc-500">
        When a customer is unhappy and has a service redone by a <span className="font-medium">different</span>{' '}
        provider, record it here. The service&apos;s commission moves from the original provider (on the
        original date) to the redo provider (on the redo date) — they show as{' '}
        <span className="font-medium text-orange-700">REDO</span> lines in the reports.
      </p>
      <RedoManager initialRedos={redos} providers={providers.filter((p) => p.active)} />
    </main>
  );
}
