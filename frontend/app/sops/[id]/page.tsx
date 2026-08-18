import Link from 'next/link';
import { serverApi } from '../../lib/serverApi';
import { localized, t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import SopDetailView from '../SopDetailView';

// Shareable, permission-checked deep link to one SOP — GET /api/sops/{id} enforces the same
// audience rule the /sops list already uses, so opening this link never shows anyone a SOP their
// role isn't meant to see.
export default async function SopDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const sopId = Number(id);
  const [me, sop] = await Promise.all([
    serverApi.getMe(),
    Number.isFinite(sopId) ? serverApi.getSop(sopId) : Promise.resolve(null),
  ]);
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <PageHeader title={sop ? localized(lang, sop.title, sop.titleRu) : t(lang, 'sopTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <Link href="/sops" className="text-sm text-zinc-500 underline hover:text-zinc-700">
        {t(lang, 'back')}
      </Link>

      {!sop ? (
        <p className="mt-6 rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">
          {t(lang, 'sopArticleNotFound')}
        </p>
      ) : (
        <SopDetailView initialSop={sop} role={me.role} language={lang} />
      )}
    </main>
  );
}
