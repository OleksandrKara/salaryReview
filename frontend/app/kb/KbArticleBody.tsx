'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import type { KbArticle, Language } from '../lib/types';

const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

/** Read view with a per-article EN/RU toggle. Defaults to the reader's preferred language, falling
 * back to English when there's no Russian; the toggle only appears when a translation exists.
 * Shared by the inline list view (KbManager) and the standalone shareable-link page (/kb/[id]).
 */
export default function KbArticleBody({ article, defaultLang }: { article: KbArticle; defaultLang: Language }) {
  const hasRu = !!(article.bodyRu && article.bodyRu.trim());
  const [lang, setLang] = useState<Language>(defaultLang === 'RU' && hasRu ? 'RU' : 'EN');
  const content = lang === 'RU' && hasRu ? article.bodyRu! : article.body;
  const pill = (l: Language) =>
    `text-xs ${lang === l ? 'font-semibold text-zinc-700' : 'text-zinc-400 hover:text-zinc-600'}`;

  return (
    <div data-color-mode="light">
      {hasRu ? (
        <div className="mb-2 flex items-center gap-1">
          <button type="button" onClick={() => setLang('EN')} className={pill('EN')}>EN</button>
          <span className="text-zinc-300">/</span>
          <button type="button" onClick={() => setLang('RU')} className={pill('RU')}>RU</button>
        </div>
      ) : null}
      <div className="text-sm">
        <Markdown source={content || '_(empty)_'} />
      </div>
    </div>
  );
}
