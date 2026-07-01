import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import KbManager from './KbManager';

// Knowledge base authoring. Owners/managers create and edit articles (markdown + AI drafting);
// providers get a read-only view of articles shared with them. Sync to the assistant happens on
// the RAG admin page. Role + initial (role-filtered) list are fetched server-side.
export default async function KbPage() {
  const [me, articles] = await Promise.all([serverApi.getMe(), serverApi.listKbArticles()]);
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <PageHeader title={t(lang, 'kbTitle')} role={me.role} language={lang} />
      <p className="mt-1 text-sm text-zinc-500">
        {t(lang, 'kbDesc')}
        {me.role !== 'PROVIDER' ? t(lang, 'kbDescEdit') : t(lang, 'kbDescReadOnly')}
      </p>
      <KbManager role={me.role} language={me.preferredLanguage} initialArticles={articles} />
    </main>
  );
}
