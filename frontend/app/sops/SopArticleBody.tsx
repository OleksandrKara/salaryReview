'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import { t } from '../lib/i18n';
import type { Language, Sop, SopVersion } from '../lib/types';

const Markdown = dynamic(
  () => import('@uiw/react-md-editor').then((m) => ({ default: m.default.Markdown })),
  { ssr: false },
);

// v2+ with an author-written note is "a change worth flagging" — same rule the list's badge and
// the reader's "what changed" callout both use. Shared by the inline list view (SopList) and the
// standalone shareable-link page (/sops/[id]).
export function hasUnreadChange(s: Sop): boolean {
  const v = s.currentVersion;
  if (!v || v.versionNumber < 2) return false;
  return !!(v.changeNote?.trim() || v.changeNoteRu?.trim());
}

export function NewBadgeIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2l2.4 6.6L21 11l-6.6 2.4L12 20l-2.4-6.6L3 11l6.6-2.4z" />
    </svg>
  );
}

// SOP content with a per-SOP EN/RU toggle. Defaults to the reader's preferred language, falling back
// to English when there's no Russian; the toggle only appears when a translation exists.
export function SopArticleBody({ version, defaultLang }: { version: SopVersion | null; defaultLang: Language }) {
  const hasRu = !!(version?.bodyRu && version.bodyRu.trim());
  const [lang, setLang] = useState<Language>(defaultLang === 'RU' && hasRu ? 'RU' : 'EN');
  const content = lang === 'RU' && hasRu ? version!.bodyRu! : version?.body;
  const pill = (l: Language) =>
    `text-xs ${lang === l ? 'font-semibold text-zinc-700' : 'text-zinc-400 hover:text-zinc-600'}`;

  // "What changed" — only from v2 on, and only when the author actually wrote one; blank shows
  // nothing. Falls back to English like the body does.
  const hasChangeNote = !!(version?.changeNote?.trim() || version?.changeNoteRu?.trim());
  const showChangeNote = (version?.versionNumber ?? 0) >= 2 && hasChangeNote;
  const changeNote = lang === 'RU' && version?.changeNoteRu?.trim() ? version.changeNoteRu : version?.changeNote;

  return (
    <div data-color-mode="light">
      {hasRu ? (
        <div className="mb-2 flex items-center gap-1">
          <button type="button" onClick={() => setLang('EN')} className={pill('EN')}>EN</button>
          <span className="text-zinc-300">/</span>
          <button type="button" onClick={() => setLang('RU')} className={pill('RU')}>RU</button>
        </div>
      ) : null}
      {showChangeNote ? (
        <div className="mb-3 rounded-xl bg-amber-50 p-3 text-sm text-amber-900 ring-2 ring-amber-300">
          <p className="mb-1 flex items-center gap-1 text-xs font-bold uppercase tracking-wide text-amber-700">
            <NewBadgeIcon className="h-3 w-3" /> {t(lang, 'sopWhatChanged')}
          </p>
          <div data-color-mode="light">
            <Markdown source={changeNote || ''} style={{ background: 'transparent', fontSize: '0.875rem' }} />
          </div>
        </div>
      ) : null}
      <div className="text-sm">
        <Markdown source={content || '_(no content)_'} />
      </div>
    </div>
  );
}
