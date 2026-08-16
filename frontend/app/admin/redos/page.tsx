import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import SetupRequiredNotice from '../../components/SetupRequiredNotice';
import RedoManager from './RedoManager';

// Owner/manager: redos. Recording a redo moves a service's commission from the original provider to
// the provider who redid it. The backend gates /api/redos by role; the proxy keeps providers out of
// /admin.
export default async function RedosPage() {
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;

  const squareConnection = await serverApi.getSquareConnection().catch(() => null);
  if (squareConnection && !squareConnection.accessTokenSet) {
    return (
      <main className="mx-auto max-w-4xl p-4 sm:p-8">
        <PageHeader title={t(lang, 'mgrRedos')} role={me.role} language={lang} />
        <SetupRequiredNotice
          title="Connect Square to manage redos"
          message="A redo moves commission against real Square bookings, which needs a Square connection first."
          // Managers can't reach the Square settings page — only show the CTA to an owner.
          ctaHref={me.role === 'OWNER' ? '/owner/settings/square' : undefined}
          ctaLabel={me.role === 'OWNER' ? 'Connect Square' : undefined}
        />
      </main>
    );
  }

  const [redos, providers] = await Promise.all([serverApi.listRedos(), serverApi.listProviders()]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'mgrRedos')} role={me.role} language={lang} />
      <p className="mb-6 text-xs text-zinc-500">{t(lang, 'redoDesc')}</p>
      <RedoManager initialRedos={redos} providers={providers.filter((p) => p.active)} language={lang} />
    </main>
  );
}
