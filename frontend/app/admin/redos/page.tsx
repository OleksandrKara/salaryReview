import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
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
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'mgrRedos')} role={me.role} language={lang} />
      <p className="mb-6 text-xs text-zinc-500">{t(lang, 'redoDesc')}</p>
      <RedoManager initialRedos={redos} providers={providers.filter((p) => p.active)} language={lang} />
    </main>
  );
}
