import Link from 'next/link';
import { serverApi } from '../../lib/serverApi';
import { localized, t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import ShareLinkButton from '../../components/ShareLinkButton';
import KbArticleBody from '../KbArticleBody';
import { SyncBadge } from '../KbSyncBadge';

// Shareable, permission-checked deep link to one KB article — GET /api/kb-articles/{id} enforces
// the same visibleRoles filter the /kb list already uses, so opening this link never shows
// anyone content their role wouldn't otherwise see.
export default async function KbArticlePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const articleId = Number(id);
  const [me, article] = await Promise.all([
    serverApi.getMe(),
    Number.isFinite(articleId) ? serverApi.getKbArticle(articleId) : Promise.resolve(null),
  ]);
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <PageHeader title={article ? localized(lang, article.title, article.titleRu) : t(lang, 'kbTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <Link href="/kb" className="text-sm text-zinc-500 underline hover:text-zinc-700">
        {t(lang, 'back')}
      </Link>

      {!article ? (
        <p className="mt-6 rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">
          {t(lang, 'kbArticleNotFound')}
        </p>
      ) : (
        <div className="mt-4 rounded-lg p-4 ring-1 ring-zinc-200">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <span className="text-xs text-zinc-400">{article.category}</span>
              <div className="mt-1 flex items-center gap-1.5">
                <SyncBadge status={article.syncStatus} />
                {article.bodyRu ? (
                  <span className="rounded bg-indigo-50 px-1.5 py-0.5 text-[10px] text-indigo-700">RU</span>
                ) : null}
              </div>
            </div>
            <ShareLinkButton path={`/kb/${article.id}`} title={localized(lang, article.title, article.titleRu)} />
          </div>
          <div className="mt-4 border-t border-zinc-100 pt-4">
            <KbArticleBody article={article} defaultLang={lang ?? 'EN'} />
          </div>
        </div>
      )}
    </main>
  );
}
